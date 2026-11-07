package com.eva.cloudtranscribe.live

import com.eva.cloudtranscribe.api.CallOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One recording's advanced live-cloud session.
 * - Audio arrives as PCM copies from the live capture stream (the recorder's output file is never read or uploaded).
 * - Every [LiveCloudSettings.transcribeIntervalMs] of recorded audio, the new samples become an immutable chunk
 *   placed in a bounded queue; ONE worker transcribes chunks in order (no ever-growing backlog).
 * - Key-point summary runs at most one at a time, gated by [SummaryGate]; failures keep the last good key points.
 * - Results are applied only while [sessionId] is current (stale results after stop/cancel are ignored).
 * Nothing here ever blocks or alters audio recording.
 */
class LiveCloudSession(
	private val scope: CoroutineScope,
	private val settings: LiveCloudSettings,
	val sessionId: String,
	private val transcribe: suspend (samples: ShortArray, startMs: Long, endMs: Long) -> CallOutcome,
	private val summarize: suspend (prompt: String) -> CallOutcome,
	private val sampleRate: Int = 16_000,
	queueCapacity: Int = 3,
	transcribeModel: String? = null,
) {
	data class Chunk(val startMs: Long, val endMs: Long, val samples: ShortArray)

	private val lock = Any()
	private val guard = SessionGuard().also { it.start(sessionId) }
	private val queue = BoundedChunkQueue<Chunk>(queueCapacity)
	private val wake = Channel<Unit>(Channel.CONFLATED)

	private var buffer = ShortArray(sampleRate * 8)
	private var bufferLen = 0
	private var bufferStartSample = 0L
	private var totalSamples = 0L
	private val intervalSamples = settings.transcribeIntervalMs * sampleRate / 1000

	private var offline: List<OfflineLine> = emptyList()
	private var successfulChunks = 0
	private var contentVersion = 0L
	private var summarizedVersion = 0L
	private var lastSummaryAtMs: Long? = null
	private var summarizedSpanCount = 0
	private var summaryJob: Job? = null
	@Volatile private var transcribing = false
	@Volatile private var closed = false

	private val _state = MutableStateFlow(
		LiveCloudState(active = true, phase = LiveCloudPhase.WAITING_FIRST_CHUNK, summaryModel = settings.summaryModel, transcribeModel = transcribeModel)
	)
	val state: StateFlow<LiveCloudState> = _state.asStateFlow()

	private val worker: Job = scope.launch {
		for (signal in wake) {
			while (true) {
				val chunk = queue.poll() ?: break
				process(chunk)
			}
		}
	}

	val elapsedMs: Long get() = synchronized(lock) { totalSamples * 1000 / sampleRate }

	/** Cheap, non-suspending; copies samples. Called from the PCM stream collector (not the recorder). */
	fun onAudio(samples: ShortArray, length: Int) {
		if (closed || length <= 0) return
		synchronized(lock) {
			ensureCapacity(bufferLen + length)
			System.arraycopy(samples, 0, buffer, bufferLen, length)
			bufferLen += length
			totalSamples += length
			if (bufferLen >= intervalSamples) cutChunkLocked()
		}
		maybeSummarize()
	}

	/** Latest offline finalized lines (whole list, same audio timeline). */
	fun onOfflineLines(lines: List<OfflineLine>) {
		if (closed) return
		val changed = synchronized(lock) {
			if (lines == offline) false else { offline = lines; if (settings.offlineSupplement) contentVersion++; true }
		}
		if (changed) publishProvisional()
	}

	private fun ensureCapacity(n: Int) {
		if (n <= buffer.size) return
		buffer = buffer.copyOf(maxOf(n, buffer.size * 2))
	}

	private fun cutChunkLocked(): Boolean {
		if (bufferLen == 0) return false
		val startMs = bufferStartSample * 1000 / sampleRate
		val endSample = bufferStartSample + bufferLen
		val chunk = Chunk(startMs, endSample * 1000 / sampleRate, buffer.copyOf(bufferLen))
		bufferStartSample = endSample
		bufferLen = 0
		if (buffer.size > sampleRate * 120) buffer = ShortArray(sampleRate * 8)
		val dropped = queue.offer(chunk)
		if (dropped != null) {
			val lost = dropped.endMs - dropped.startMs
			_state.update {
				it.copy(
					skippedMs = it.skippedMs + lost,
					cloudSpans = (it.cloudSpans + CloudSpan(dropped.startMs, dropped.endMs, "", ok = false, error = "雲端跟不上，此段未上傳（錄音完整保存，可事後重轉）")).sortedBy { s -> s.startMs },
				)
			}
		}
		_state.update { it.copy(pendingChunks = queue.size()) }
		wake.trySend(Unit)
		return true
	}

	private suspend fun process(chunk: Chunk) {
		if (!guard.isCurrent(sessionId)) return
		transcribing = true
		_state.update { it.copy(phase = LiveCloudPhase.TRANSCRIBING, pendingChunks = queue.size()) }
		var outcome = transcribe(chunk.samples, chunk.startMs, chunk.endMs)
		if (outcome is CallOutcome.Failure && outcome.retryable && guard.isCurrent(sessionId)) {
			outcome = transcribe(chunk.samples, chunk.startMs, chunk.endMs)
		}
		transcribing = false
		if (!guard.isCurrent(sessionId)) return
		val span = when (outcome) {
			is CallOutcome.Text -> CloudSpan(chunk.startMs, chunk.endMs, outcome.text)
			CallOutcome.Empty -> CloudSpan(chunk.startMs, chunk.endMs, "")
			CallOutcome.ContentFiltered -> CloudSpan(chunk.startMs, chunk.endMs, "", ok = false, error = "內容被服務過濾")
			is CallOutcome.Failure -> CloudSpan(chunk.startMs, chunk.endMs, "", ok = false, error = outcome.message)
		}
		synchronized(lock) {
			if (span.ok) { successfulChunks++; if (span.text.isNotBlank()) contentVersion++ }
		}
		_state.update {
			it.copy(
				cloudSpans = (it.cloudSpans + span).sortedBy { s -> s.startMs },
				transcribeError = if (span.ok) null else span.error,
				phase = LiveCloudPhase.IDLE,
				pendingChunks = queue.size(),
			)
		}
		publishProvisional()
		maybeSummarize()
	}

	private fun publishProvisional() {
		val lines = synchronized(lock) { offline }
		_state.update { s ->
			s.copy(provisional = if (settings.offlineSupplement) TranscriptMerge.provisional(s.cloudSpans, lines) else emptyList())
		}
	}

	private fun maybeSummarize(force: Boolean = false): Job? {
		val elapsed = elapsedMs
		val run = synchronized(lock) {
			val ok = if (force) settings.isActive && contentVersion > summarizedVersion && (!settings.requireCloudBeforeFirstSummary || successfulChunks > 0 || lastSummaryAtMs != null)
			else SummaryGate.shouldRun(settings, elapsed, lastSummaryAtMs, successfulChunks, contentVersion, summarizedVersion, summaryJob?.isActive == true)
			if (ok) lastSummaryAtMs = elapsed
			ok
		}
		if (!run || closed && !force) return null
		val versionAtStart = synchronized(lock) { contentVersion }
		val snapshot = _state.value
		val okSpans = snapshot.cloudSpans.filter { it.ok }
		val newCloud = TranscriptMerge.cloudText(okSpans.drop(summarizedSpanCount))
		val provisionalText = snapshot.provisional.joinToString("\n") { it.text }
		val prompt = TranscriptMerge.summaryPrompt(settings.summaryPrompt, snapshot.keyPoints, newCloud, provisionalText)
		val job = scope.launch {
			_state.update { it.copy(phase = LiveCloudPhase.SUMMARIZING) }
			val outcome = summarize(prompt)
			if (!guard.isCurrent(sessionId)) return@launch
			when (outcome) {
				is CallOutcome.Text -> {
					synchronized(lock) { summarizedVersion = versionAtStart; summarizedSpanCount = okSpans.size }
					_state.update { it.copy(keyPoints = outcome.text, keyPointsAtMs = elapsed, summaryError = null, phase = if (transcribing) LiveCloudPhase.TRANSCRIBING else LiveCloudPhase.IDLE) }
				}
				else -> _state.update {
					it.copy(
						summaryError = when (outcome) {
							is CallOutcome.Failure -> outcome.message
							CallOutcome.ContentFiltered -> "摘要內容被服務過濾"
							else -> "摘要回應為空"
						},
						phase = if (transcribing) LiveCloudPhase.TRANSCRIBING else LiveCloudPhase.IDLE,
					)
				}
			}
		}
		synchronized(lock) { summaryJob = job }
		return job
	}

	/**
	 * Stop: flushes the tail audio (>= 1 s) as a final chunk, waits (bounded) for queued transcription, then runs one
	 * final summary if there is new content. Returns the final state. Audio saving never waits on this.
	 */
	suspend fun finish(transcribeTimeoutMs: Long = 90_000, summaryTimeoutMs: Long = 60_000): LiveCloudState {
		if (closed) return _state.value
		closed = true
		_state.update { it.copy(phase = LiveCloudPhase.FINISHING) }
		synchronized(lock) { if (bufferLen >= sampleRate) cutChunkLocked() else bufferLen = 0 }
		wake.close()
		withTimeoutOrNull(transcribeTimeoutMs) { worker.join() } ?: run { worker.cancel() }
		synchronized(lock) { summaryJob }?.let { withTimeoutOrNull(summaryTimeoutMs) { it.join() } }
		maybeSummarize(force = true)?.let { j -> withTimeoutOrNull(summaryTimeoutMs) { j.join() } ?: j.cancel() }
		guard.close()
		_state.update { it.copy(phase = LiveCloudPhase.DONE, pendingChunks = queue.size()) }
		return _state.value
	}

	/** Cancel (recording discarded / service destroyed): drops everything, ignores late results. */
	fun cancel() {
		closed = true
		guard.close()
		queue.clear()
		wake.close()
		worker.cancel()
		summaryJob?.cancel()
	}
}
