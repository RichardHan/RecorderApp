package com.eva.cloudtranscribe.live

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings

import com.eva.cloudtranscribe.api.CallOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One recording's advanced live-cloud session.
 * - Audio arrives as PCM copies from the live capture stream (the recorder's output file is never read or uploaded).
 * - Every [LiveCloudSettings.transcribeIntervalMs] of recorded audio, the new samples become an immutable chunk
 *   placed in a bounded queue. Up to [LiveCloudSettings.MAX_PARALLEL_TRANSCRIBES] uploads may run at once; results
 *   are applied in recorded-audio order so a slow reply never blocks the next chunk's upload, and later text never
 *   appears before earlier audio.
 * - When [LiveCloudSettings.enhanceContextQuality] is on, later chunks include a short leading audio overlap and
 *   previous-text context; a text-only refine may then replace the raw text asynchronously (raw is shown first).
 * - Key-point summary runs at most one at a time, gated by [SummaryGate]; failures keep the last good key points.
 * - Results are applied only while [sessionId] is current (stale results after stop/cancel are ignored).
 * Nothing here ever blocks or alters audio recording.
 */
class LiveCloudSession(
	private val scope: CoroutineScope,
	private val settings: LiveCloudSettings,
	val sessionId: String,
	private val transcribe: suspend (LiveTranscribeJob) -> CallOutcome,
	private val summarize: suspend (prompt: String) -> CallOutcome,
	private val sampleRate: Int = 16_000,
	queueCapacity: Int = 3,
	transcribeModel: String? = null,
	private val meetingNotes: () -> String = { settings.meetingNotes },
	private val transcribePrompt: String = com.eva.cloudtranscribe.engine.Prompts.TRANSCRIBE,
	private val seedTerms: List<String> = emptyList(),
	private val refine: (suspend (String) -> CallOutcome)? = null,
	private val clock: () -> Long = { System.currentTimeMillis() },
) {
	data class Chunk(
		val startMs: Long,
		val endMs: Long,
		val samples: ShortArray,
		val overlapMs: Long,
		val seq: Int,
	)

	private class FinishedChunk(
		val chunk: Chunk,
		val outcome: CallOutcome,
		val transcribeMs: Long,
		val uploadedMs: Long,
	)

	private val lock = Any()
	// Keep dequeue, previous-text lookup and publication in the same serial apply lane.
	private val applyMutex = Mutex()
	private val guard = SessionGuard().also { it.start(sessionId) }
	private val queue = BoundedChunkQueue<Chunk>(queueCapacity)

	private var buffer = ShortArray(sampleRate * 8)
	private var bufferLen = 0
	private var bufferStartSample = 0L
	private var totalSamples = 0L
	private val intervalSamples = settings.transcribeIntervalMs * sampleRate / 1000
	private var overlapTail = ShortArray(0)

	private var offline: List<OfflineLine> = emptyList()
	private var successfulChunks = 0
	private var contentVersion = 0L
	private var summarizedVersion = 0L
	private var lastSummaryAtMs: Long? = null
	private var summarizedSpanCount = 0
	private var summaryJob: Job? = null
	private var nextSeq = 0
	private var nextApplySeq = 0
	private var activeTranscribes = 0
	private val finished = sortedMapOf<Int, FinishedChunk>()
	private val skippedSequences = mutableSetOf<Int>()
	private val transcribeJobs = mutableListOf<Job>()
	private val refineJobs = mutableListOf<Job>()
	private var knownTerms: List<String> = LiveKnownTerms.merge(emptyList(), seedTerms)
	@Volatile private var transcribing = false
	@Volatile private var closed = false
	/** When true, audio timeline still advances but no new chunks are cut/uploaded and no new summaries are scheduled. */
	@Volatile private var uploadsPaused = false

	private val _state = MutableStateFlow(
		LiveCloudState(active = true, phase = LiveCloudPhase.WAITING_FIRST_CHUNK, summaryModel = settings.summaryModel, transcribeModel = transcribeModel)
	)
	val state: StateFlow<LiveCloudState> = _state.asStateFlow()

	val elapsedMs: Long get() = synchronized(lock) { totalSamples * 1000 / sampleRate }

	val isUploadsPaused: Boolean get() = uploadsPaused

	/**
	 * Mid-recording pause/resume of cloud uploads. The recorded-audio timeline keeps advancing.
	 * Turning off drops the uncut buffer (no partial upload) and stops scheduling new chunks/summaries;
	 * already-queued or in-flight work may finish. Turning back on starts a new chunk at the current
	 * position so there is no overlap with earlier uploads and no mix with another recording.
	 */
	fun setUploadsPaused(paused: Boolean) {
		if (closed) return
		synchronized(lock) {
			if (uploadsPaused == paused) return
			uploadsPaused = paused
			if (paused) {
				if (bufferLen > 0) {
					bufferStartSample += bufferLen
					bufferLen = 0
				}
				// a gap after resume must not reuse pre-pause audio as overlap context
				overlapTail = ShortArray(0)
			}
		}
		if (!paused) maybeSummarize()
	}

	/** Cheap, non-suspending; copies samples. Called from the PCM stream collector (not the recorder). */
	fun onAudio(samples: ShortArray, length: Int) {
		if (closed || length <= 0) return
		synchronized(lock) {
			totalSamples += length
			if (!uploadsPaused) {
				ensureCapacity(bufferLen + length)
				System.arraycopy(samples, 0, buffer, bufferLen, length)
				bufferLen += length
				while (bufferLen >= intervalSamples) cutChunkLocked()
			} else {
				// keep the next resume chunk aligned with the recorded-audio timeline
				if (bufferLen == 0) bufferStartSample = totalSamples
			}
		}
		if (!uploadsPaused) {
			tryStart()
			maybeSummarize()
		}
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
		val take = if (closed) bufferLen else minOf(bufferLen.toLong(), intervalSamples).toInt().let { if (it <= 0) bufferLen else it }
		val startMs = bufferStartSample * 1000 / sampleRate
		val fresh = buffer.copyOf(take)
		if (bufferLen > take) {
			System.arraycopy(buffer, take, buffer, 0, bufferLen - take)
			bufferLen -= take
		} else {
			bufferLen = 0
		}
		bufferStartSample += take
		if (buffer.size > sampleRate * 120 && bufferLen == 0) buffer = ShortArray(sampleRate * 8)
		val overlapMs = if (settings.enhanceContextQuality) settings.effectiveOverlapMs else 0L
		val leading = if (overlapMs > 0L) overlapTail else ShortArray(0)
		val uploaded = LiveAudioOverlap.prepend(leading, fresh)
		val endMs = bufferStartSample * 1000 / sampleRate
		overlapTail = if (overlapMs > 0L) LiveAudioOverlap.tail(fresh, overlapMs, sampleRate) else ShortArray(0)
		val chunk = Chunk(startMs, endMs, uploaded, if (leading.isEmpty()) 0L else overlapMs, nextSeq++)
		val dropped = queue.offer(chunk)
		if (dropped != null) {
			skippedSequences.add(dropped.seq)
			val lost = dropped.endMs - dropped.startMs
			_state.update {
				it.copy(
					skippedMs = it.skippedMs + lost,
					cloudSpans = (it.cloudSpans + CloudSpan(dropped.startMs, dropped.endMs, "", ok = false, error = UiStrings.get(StringsRes.string.data_cloudtranscribe_upload_fell_behind_this_segment_was_skipped, "雲端跟不上，此段未上傳（錄音完整保存，可事後重轉）"))).sortedBy { s -> s.startMs },
				)
			}
		}
		_state.update { it.copy(pendingChunks = queue.size(), inFlightTranscribes = activeTranscribes) }
		return true
	}

	private fun tryStart() {
		val toRun = mutableListOf<Chunk>()
		synchronized(lock) {
			val cap = LiveCloudSettings.MAX_PARALLEL_TRANSCRIBES
			while (activeTranscribes < cap) {
				val chunk = queue.poll() ?: break
				activeTranscribes++
				toRun += chunk
			}
			transcribing = activeTranscribes > 0
			_state.update { it.copy(pendingChunks = queue.size(), inFlightTranscribes = activeTranscribes) }
		}
		toRun.forEach { chunk ->
			val job = scope.launch { process(chunk) }
			synchronized(lock) { transcribeJobs += job }
		}
	}

	private suspend fun process(chunk: Chunk) {
		if (!guard.isCurrent(sessionId)) {
			onProcessDone()
			return
		}
		_state.update {
			it.copy(phase = LiveCloudPhase.TRANSCRIBING, pendingChunks = queue.size(), inFlightTranscribes = activeTranscribes)
		}
		val previous: String
		val terms: List<String>
		synchronized(lock) {
			previous = previousCloudTailLocked()
			terms = knownTerms
		}
		val prompt = LiveTranscribePrompt.build(
			base = transcribePrompt,
			overlapMs = chunk.overlapMs,
			previousSentences = previous,
			knownTerms = terms,
			enhance = settings.enhanceContextQuality,
		)
		val job = LiveTranscribeJob(
			samples = chunk.samples,
			startMs = chunk.startMs,
			endMs = chunk.endMs,
			overlapMs = chunk.overlapMs,
			prompt = prompt,
			previousSentences = previous,
			knownTerms = terms,
		)
		val t0 = clock()
		var outcome: CallOutcome? = null
		try {
			outcome = try {
				val first = transcribe(job)
				if (first is CallOutcome.Failure && first.retryable && guard.isCurrent(sessionId)) {
					transcribe(job)
				} else {
					first
				}
			} catch (ce: CancellationException) {
				throw ce
			} catch (e: Exception) {
				CallOutcome.Failure(e.message ?: "transcribe failed", retryable = false)
			}
		} finally {
			val transcribeMs = (clock() - t0).coerceAtLeast(0L)
			val uploadedMs = chunk.samples.size.toLong() * 1000L / sampleRate
			synchronized(lock) {
				val finalOutcome = outcome
				if (finalOutcome != null) {
					finished[chunk.seq] = FinishedChunk(chunk, finalOutcome, transcribeMs, uploadedMs)
				} else {
					skippedSequences += chunk.seq
				}
			}
			onProcessDone()
		}
		drainApply()
	}

	private fun onProcessDone() {
		synchronized(lock) {
			activeTranscribes = (activeTranscribes - 1).coerceAtLeast(0)
			transcribing = activeTranscribes > 0
			_state.update {
				it.copy(
					inFlightTranscribes = activeTranscribes,
					pendingChunks = queue.size(),
					phase = if (!transcribing && queue.size() == 0 && it.phase == LiveCloudPhase.TRANSCRIBING) {
						LiveCloudPhase.IDLE
					} else {
						it.phase
					},
				)
			}
		}
		tryStart()
	}

	private suspend fun drainApply() = applyMutex.withLock {
		while (true) {
			val ready = synchronized(lock) {
				if (!guard.isCurrent(sessionId)) {
					null
				} else {
					while (skippedSequences.remove(nextApplySeq)) nextApplySeq++
					val item = finished.remove(nextApplySeq)
					if (item != null) nextApplySeq++
					item
				}
			} ?: break
			applyFinished(ready)
		}
	}

	private fun applyFinished(ready: FinishedChunk) {
		if (!guard.isCurrent(sessionId)) return
		val chunk = ready.chunk
		val prevText = synchronized(lock) { previousCloudTextLocked() }
		// The prepended samples came from the immediately adjacent audio only, never an older span.
		val overlapText = if (chunk.overlapMs > 0L) {
			_state.value.cloudSpans.lastOrNull { it.endMs == chunk.startMs && it.covers }?.text.orEmpty()
		} else ""
		val span = spanOf(chunk, ready.outcome, overlapText)
		synchronized(lock) {
			if (span.ok && !span.suspicious) {
				successfulChunks++
				if (span.covers) {
					contentVersion++
					knownTerms = LiveKnownTerms.merge(knownTerms, LiveKnownTerms.extract(span.text))
				}
			}
		}
		val latency = ChunkLatency(
			startMs = chunk.startMs,
			endMs = chunk.endMs,
			overlapMs = chunk.overlapMs,
			uploadedMs = ready.uploadedMs,
			transcribeMs = ready.transcribeMs,
		)
		_state.update {
			it.copy(
				cloudSpans = (it.cloudSpans + span).sortedBy { s -> s.startMs },
				transcribeError = if (span.ok && !span.suspicious) null else span.error,
				phase = when {
					it.phase == LiveCloudPhase.FINISHING || it.phase == LiveCloudPhase.DONE -> it.phase
					activeTranscribes > 0 -> LiveCloudPhase.TRANSCRIBING
					else -> LiveCloudPhase.IDLE
				},
				pendingChunks = queue.size(),
				inFlightTranscribes = activeTranscribes,
				lastChunkLatency = latency,
			)
		}
		publishProvisional()
		maybeSummarize()
		if (span.covers && settings.effectiveRefine && refine != null) startRefine(span, prevText, latency)
	}

	private fun startRefine(span: CloudSpan, previousText: String, latency: ChunkLatency) {
		val refineFn = refine ?: return
		val terms = synchronized(lock) { knownTerms }
		val tail = LiveTranscriptOverlap.lastSentences(previousText)
		val job = scope.launch {
			val t0 = clock()
			val outcome = runCatching { refineFn(LiveBoundaryRefine.prompt(tail, span.text, terms)) }
				.getOrElse { CallOutcome.Failure(it.message ?: "refine failed", retryable = false) }
			val refineMs = (clock() - t0).coerceAtLeast(0L)
			if (!guard.isCurrent(sessionId)) return@launch
			val accepted = (outcome as? CallOutcome.Text)?.text?.let { LiveBoundaryRefine.accept(span.text, it) }
			if (accepted == null || accepted == span.text) {
				_state.update { it.copy(lastChunkLatency = latency.copy(refineMs = refineMs)) }
				return@launch
			}
			synchronized(lock) {
				knownTerms = LiveKnownTerms.merge(knownTerms, LiveKnownTerms.extract(accepted))
			}
			_state.update { s ->
				s.copy(
					cloudSpans = s.cloudSpans.map { if (it.startMs == span.startMs && it.endMs == span.endMs) it.copy(text = accepted) else it },
					lastChunkLatency = latency.copy(refineMs = refineMs),
				)
			}
			publishProvisional()
			maybeSummarize()
		}
		synchronized(lock) { refineJobs += job }
	}

	private fun spanOf(chunk: Chunk, outcome: CallOutcome, overlapText: String): CloudSpan {
		fun cleaned(raw: String): String =
			if (chunk.overlapMs > 0L) LiveTranscriptOverlap.trimLeadingOverlap(overlapText, raw) else raw.trim()
		return when (outcome) {
			is CallOutcome.Text -> when {
				com.eva.cloudtranscribe.engine.Prompts.isNoSpeech(outcome.text) -> CloudSpan(chunk.startMs, chunk.endMs, "")
				com.eva.cloudtranscribe.engine.RepetitionGuard.isPathological(outcome.text) ->
					CloudSpan(chunk.startMs, chunk.endMs, outcome.text, ok = true, suspicious = true, error = com.eva.cloudtranscribe.engine.RepetitionGuard.WARNING)
				else -> CloudSpan(chunk.startMs, chunk.endMs, cleaned(outcome.text))
			}
			CallOutcome.Empty -> CloudSpan(chunk.startMs, chunk.endMs, "")
			CallOutcome.ContentFiltered -> CloudSpan(chunk.startMs, chunk.endMs, "", ok = false, error = UiStrings.get(StringsRes.string.data_cloudtranscribe_content_filtered_by_service, "內容被服務過濾"))
			is CallOutcome.Failure -> CloudSpan(chunk.startMs, chunk.endMs, "", ok = false, error = outcome.message)
		}
	}

	private fun previousCloudTextLocked(): String =
		_state.value.cloudSpans.filter { it.covers }.maxByOrNull { it.startMs }?.text.orEmpty()

	private fun previousCloudTailLocked(): String =
		LiveTranscriptOverlap.lastSentences(previousCloudTextLocked())

	private fun publishProvisional() {
		val lines = synchronized(lock) { offline }
		_state.update { s ->
			s.copy(
				provisional = if (settings.offlineSupplement) TranscriptMerge.provisional(s.cloudSpans, lines) else emptyList(),
				offlineTimed = lines,
			)
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
		if (!run || (closed && !force) || (uploadsPaused && !force)) return null
		val versionAtStart = synchronized(lock) { contentVersion }
		val snapshot = _state.value
		val okSpans = snapshot.cloudSpans.filter { it.covers }
		val newCloud = TranscriptMerge.cloudText(okSpans.drop(summarizedSpanCount))
		val provisionalText = snapshot.provisional.filterNot { com.eva.cloudtranscribe.engine.RepetitionGuard.isPathological(it.text) }.joinToString("\n") { it.text }
		val prompt = TranscriptMerge.summaryPrompt(settings.summaryPrompt, snapshot.keyPoints, newCloud, provisionalText, meetingNotes = runCatching { meetingNotes() }.getOrDefault(settings.meetingNotes))
		val job = scope.launch {
			_state.update { it.copy(phase = LiveCloudPhase.SUMMARIZING) }
			val outcome = summarize(prompt)
			if (!guard.isCurrent(sessionId)) return@launch
			when (outcome) {
				is CallOutcome.Text -> {
					synchronized(lock) { summarizedVersion = versionAtStart; summarizedSpanCount = okSpans.size }
					val accepted = LiveCloudSettings.acceptSummary(snapshot.keyPoints, outcome.text)
					_state.update {
						// no new points / garbage reply: keep the previous valid key points unchanged
						if (accepted == null) it.copy(summaryError = null, phase = if (transcribing) LiveCloudPhase.TRANSCRIBING else LiveCloudPhase.IDLE)
						else it.copy(keyPoints = accepted, keyPointsAtMs = elapsed, summaryError = null, phase = if (transcribing) LiveCloudPhase.TRANSCRIBING else LiveCloudPhase.IDLE)
					}
				}
				else -> _state.update {
					it.copy(
						summaryError = when (outcome) {
							is CallOutcome.Failure -> outcome.message
							CallOutcome.ContentFiltered -> UiStrings.get(StringsRes.string.data_cloudtranscribe_summary_filtered_by_service, "摘要內容被服務過濾")
							else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_empty_summary_response, "摘要回應為空")
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
		tryStart()
		withTimeoutOrNull(transcribeTimeoutMs) {
			while (true) {
				val pending = synchronized(lock) { queue.size() + activeTranscribes }
				if (pending == 0) break
				delay(20)
			}
		} ?: synchronized(lock) { transcribeJobs.forEach { it.cancel() } }
		drainApply()
		val pendingRefine = synchronized(lock) { refineJobs.filter { it.isActive } }
		if (pendingRefine.isNotEmpty()) {
			withTimeoutOrNull(20_000) { pendingRefine.forEach { it.join() } }
		}
		synchronized(lock) { summaryJob }?.let { withTimeoutOrNull(summaryTimeoutMs) { it.join() } }
		maybeSummarize(force = true)?.let { j -> withTimeoutOrNull(summaryTimeoutMs) { j.join() } ?: j.cancel() }
		guard.close()
		_state.update { it.copy(phase = LiveCloudPhase.DONE, pendingChunks = queue.size(), inFlightTranscribes = 0) }
		return _state.value
	}

	/** Cancel (recording discarded / service destroyed): drops everything, ignores late results. */
	fun cancel() {
		closed = true
		guard.close()
		queue.clear()
		synchronized(lock) {
			transcribeJobs.forEach { it.cancel() }
			refineJobs.forEach { it.cancel() }
			finished.clear()
			skippedSequences.clear()
			activeTranscribes = 0
			overlapTail = ShortArray(0)
			knownTerms = emptyList()
		}
		summaryJob?.cancel()
	}
}
