package com.eva.recorder.data.recorder

import android.util.Log
import com.eva.datastore.domain.repository.TranscriptionSettingsRepo
import com.eva.recorder.domain.recorder.AudioByteDataProvider
import com.eva.recorder.domain.recorder.LiveTranscript
import com.eva.recorder.domain.recorder.LiveTranscriptState
import com.eva.recorder.domain.recorder.LiveTranscriptStatus
import com.eva.recorder.domain.recorder.TranscriptionProvider
import com.eva.transcribe.domain.AudioTranscriptor
import com.eva.transcribe.domain.repository.STTModelsRepository
import com.eva.transcribe.offline.LiveAsrController
import com.eva.transcribe.offline.LiveAsrRun
import com.eva.transcribe.offline.LiveAsrRunResult
import com.eva.transcribe.offline.LiveAsrStatus
import com.eva.transcribe.offline.LiveUtterancePipeline
import com.eva.transcribe.offline.OfflineEngines
import com.eva.transcribe.offline.OfflineSpeechEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "TRANSCRIPTION_PROVIDER"

/**
 * Live on-device recognition that follows the "即時字幕" switch (TranscriptionSettings.isEnabled) during recording.
 * - Switching off flushes the current run, keeps its text and frees the model; the recording is unaffected.
 * - Switching on (re)loads the model and recognises from the current audio onwards.
 * - All transitions are serialized by [LiveAsrController]; rapid toggles are conflated.
 * Recognition runs on background dispatchers and never blocks the audio recorder.
 */
internal class TranscriptionProviderImpl(
	private val source: AudioByteDataProvider,
	private val transcriptor: AudioTranscriptor,
	private val settingsRepo: TranscriptionSettingsRepo,
	private val repository: STTModelsRepository,
	private val offlineEngine: OfflineSpeechEngine,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : TranscriptionProvider {

	private val _display = MutableStateFlow(LiveTranscriptState())
	override val transcription: Flow<LiveTranscriptState> = _display.asStateFlow()

	private val _timed = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
	override val offlineTimedLines: Flow<List<Pair<Long, String>>> = _timed.asStateFlow()
	@Volatile private var samplesSeen = 0L
	private var started = false
	private val ownsSherpa = AtomicBoolean(false)
	private var lastEngine: String = ""

	@Volatile private var lastDesired: Pair<Boolean, String?>? = null

	private val controller = LiveAsrController(scope, startRun = ::startRun, releaseModel = ::releaseModel, capturedSamples = { samplesSeen })

	override suspend fun initTranscriptions() {
		if (started) return
		started = true
		// counts captured samples from recording start so offline line times share the recorded-audio timeline
		scope.launch { source.stream.collect { (_, size) -> if (size > 0) samplesSeen += size } }
		scope.launch {
			controller.state.collect { s ->
				_display.value = LiveTranscriptState(
					finalized = s.lines, partial = s.partial,
					status = when (s.status) {
						LiveAsrStatus.OFF -> LiveTranscriptStatus.DISABLED
						LiveAsrStatus.NO_MODEL -> LiveTranscriptStatus.NO_MODEL
						LiveAsrStatus.LOADING -> LiveTranscriptStatus.LOADING
						LiveAsrStatus.LISTENING -> LiveTranscriptStatus.LISTENING
						LiveAsrStatus.DECODING -> LiveTranscriptStatus.DECODING
						LiveAsrStatus.ERROR -> LiveTranscriptStatus.ERROR
					},
					message = s.message, modelLabel = s.modelId,
				)
				_timed.value = s.timedLines
			}
		}
		// follow the switch for the whole recording
		scope.launch {
			settingsRepo.settingsFlow
				.map { it.isEnabled to it.modelId }
				.distinctUntilChanged()
				.conflate()
				.collect { (enabled, modelId) ->
					lastDesired = enabled to modelId
					try { controller.apply(enabled, modelId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
						Log.e(TAG, "apply failed", e)
					}
				}
		}
	}

	private suspend fun startRun(modelId: String): Result<LiveAsrRun> {
		val model = repository.readModelByLanguage(modelId).getOrElse {
			return Result.failure(IllegalStateException("找不到選定的離線模型，請到設定重新選擇"))
		}
		lastEngine = model.engine
		return if (OfflineEngines.isSherpa(model.engine)) {
			offlineEngine.load(modelId).getOrElse { return Result.failure(it) }
			ownsSherpa.set(true)
			val offsetMs = samplesSeen * 1000 / 16_000
			Result.success(SherpaRun(offsetMs))
		} else {
			if (transcriptor.setUp(modelId).isFailure) return Result.failure(IllegalStateException("Vosk 模型載入失敗"))
			Result.success(VoskRun())
		}
	}

	private suspend fun releaseModel() {
		if (ownsSherpa.compareAndSet(true, false)) offlineEngine.release()
		transcriptor.cleanUp()
	}

	private inner class SherpaRun(offsetMs: Long) : LiveAsrRun {
		private val runScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
		private val p = LiveUtterancePipeline(runScope) { samples -> offlineEngine.decode(samples) }
		private val _timedRun = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
		private val feeder: Job = runScope.launch { source.stream.collect { (buffer, size) -> if (size > 0) p.accept(buffer, size) } }
		init { runScope.launch { p.timedLines.collect { l -> _timedRun.value = l.map { (ms, t) -> (ms + offsetMs) to t } } } }
		override val lines: StateFlow<List<String>> get() = p.lines
		override val timedLines: StateFlow<List<Pair<Long, String>>> get() = _timedRun
		override val decoding: StateFlow<Boolean> get() = p.decoding
		override val warning: StateFlow<String?> get() = p.warning
		override suspend fun finish(): LiveAsrRunResult {
			feeder.cancel()
			val ok = p.finish()
			runScope.cancel()
			return LiveAsrRunResult(p.decodedUtterances, p.failedUtterances, p.droppedSamples, ok)
		}
		override fun cancel() { p.cancel(); runScope.cancel() }
	}

	private inner class VoskRun : LiveAsrRun {
		private val runScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
		private val finals = MutableStateFlow<List<String>>(emptyList())
		private val _partial = MutableStateFlow("")
		private val _warning = MutableStateFlow<String?>(null)
		private val _decoding = MutableStateFlow(false)
		private val empty = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
		private var dropped = 0L
		private var failed = 0
		private val queue = Channel<ShortArray>(capacity = 64)
		private val feeder: Job = runScope.launch {
			source.stream.collect { (buffer, size) ->
				if (size <= 0) return@collect
				if (queue.trySend(buffer.copyOf(size)).isFailure) { dropped += size; _warning.value = "辨識跟不上，部分音訊未辨識（錄音完整保存）" }
			}
		}
		private val worker: Job = runScope.launch {
			for (chunk in queue) {
				try {
					val r = transcriptor.recognizeAudio(chunk, chunk.size) ?: continue
					if (r.fullResult.isNotBlank()) { finals.value = finals.value + r.fullResult; _partial.value = "" } else _partial.value = r.partial
				} catch (e: CancellationException) { throw e } catch (e: Exception) {
					failed++; Log.e(TAG, "VOSK ERROR", e); _warning.value = "辨識錯誤：${e.message}"
				}
			}
		}
		override val lines: StateFlow<List<String>> get() = finals
		override val timedLines: StateFlow<List<Pair<Long, String>>> get() = empty
		override val decoding: StateFlow<Boolean> get() = _decoding
		override val warning: StateFlow<String?> get() = _warning
		override val partial: StateFlow<String> get() = _partial
		override suspend fun finish(): LiveAsrRunResult {
			feeder.cancelAndJoin(); queue.close()
			val ok = withTimeoutOrNull(30_000) { worker.join() } != null
			if (ok) {
				try {
					val tail = transcriptor.finishAudio()?.fullResult.orEmpty()
					if (tail.isNotBlank()) finals.value = finals.value + tail
					_partial.value = ""
				} catch (e: CancellationException) { throw e } catch (e: Exception) {
					failed++; _warning.value = "最後一段辨識失敗：${e.message}"
				}
			}
			runScope.cancel()
			return LiveAsrRunResult(finals.value.size, failed, dropped, ok && failed == 0)
		}
		override fun cancel() { queue.close(); runScope.cancel() }
	}

	override suspend fun beginNewRecording() {
		// same service instance reused for another recording: start clean, timeline from zero, re-apply settings
		controller.resetForNewRecording()
		samplesSeen = 0L
		_timed.value = emptyList()
		lastDesired?.let { (enabled, modelId) ->
			try { controller.apply(enabled, modelId) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.e(TAG, "re-apply failed", e) }
		}
	}

	override suspend fun finishTranscription(): LiveTranscript? {
		val agg = controller.finishAll() ?: return null
		val gapNote = if (agg.hadGaps) "\n[即時字幕未涵蓋全部錄音，部分期間未辨識（錄音完整保存，可事後重轉）]" else ""
		return LiveTranscript(
			modelId = agg.modelIds.joinToString(" / "), engine = lastEngine,
			text = agg.text + gapNote, decodedSegments = agg.decoded, failedSegments = agg.failed,
			droppedMillis = agg.droppedSamples * 1000 / 16_000, complete = agg.complete && !agg.hadGaps,
		)
	}

	override fun transcriptCleanUp() {
		controller.cancelAll()
		scope.cancel()
		transcriptor.cleanUp()
		// free the large native model once any in-flight decode has returned
		if (ownsSherpa.compareAndSet(true, false)) {
			// Keep cleanup independent of the cancelled provider job. Engine.release selects its IO dispatcher.
			CoroutineScope(scope.coroutineContext.minusKey(Job)).launch { offlineEngine.release() }
		}
	}
}
