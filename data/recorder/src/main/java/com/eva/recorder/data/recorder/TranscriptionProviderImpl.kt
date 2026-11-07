package com.eva.recorder.data.recorder

import android.util.Log
import com.eva.datastore.domain.repository.TranscriptionSettingsRepo
import com.eva.recorder.domain.recorder.AudioByteDataProvider
import com.eva.recorder.domain.recorder.LiveTranscript
import com.eva.recorder.domain.recorder.TranscriptionProvider
import com.eva.transcribe.domain.AudioTranscriptor
import com.eva.transcribe.domain.repository.STTModelsRepository
import com.eva.transcribe.offline.LiveUtterancePipeline
import com.eva.transcribe.offline.OfflineEngines
import com.eva.transcribe.offline.OfflineSpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import com.eva.recorder.domain.recorder.LiveTranscriptState
import com.eva.recorder.domain.recorder.LiveTranscriptStatus
import kotlinx.coroutines.launch

private const val TAG = "TRANSCRIPTION_PROVIDER"

/**
 * Runs only when transcription is enabled and a downloaded model is selected.
 * - sherpa-onnx models (SenseVoice / Qwen3-ASR / FireRed AED): non-streaming "分句辨識" via [LiveUtterancePipeline].
 * - Vosk: streaming; every buffer (incl. silence, which Vosk needs to finalise) goes through a bounded queue
 *   and overflow is counted and shown instead of being dropped silently.
 * Recognition runs on background dispatchers and never blocks the audio recorder.
 */
internal class TranscriptionProviderImpl(
	private val source: AudioByteDataProvider,
	private val transcriptor: AudioTranscriptor,
	private val settingsRepo: TranscriptionSettingsRepo,
	private val repository: STTModelsRepository,
	private val offlineEngine: OfflineSpeechEngine,
) : TranscriptionProvider {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val _display = MutableStateFlow(LiveTranscriptState())
	override val transcription: Flow<LiveTranscriptState> = _display.asStateFlow()

	private val _timed = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
	override val offlineTimedLines: Flow<List<Pair<Long, String>>> = _timed.asStateFlow()
	@Volatile private var samplesSeen = 0L
	private var sampleCounter: Job? = null

	private var collector: Job? = null
	private var pipeline: LiveUtterancePipeline? = null
	private var displayJob: Job? = null
	private var activeModel: Pair<String, String>? = null

	// vosk state
	private val voskFinals = ArrayList<String>()
	private var voskDropped = 0L

	override suspend fun initTranscriptions() {
		val settings = settingsRepo.setting()
		val modelId = settings.modelId
		if (!settings.isEnabled || modelId == null) {
			Log.d(TAG, "TRANSCRIPTION DISABLED OR NO MODEL SELECTED")
			_display.value = LiveTranscriptState(status = LiveTranscriptStatus.DISABLED)
			return
		}
		val model = repository.readModelByLanguage(modelId).getOrElse {
			Log.w(TAG, "Selected model missing", it)
			_display.value = LiveTranscriptState(status = LiveTranscriptStatus.ERROR, message = "找不到選定的離線模型，請到設定重新選擇")
			return
		}
		val label = modelId
		// counts captured samples from recording start so offline line times share the recorded-audio timeline
		sampleCounter = scope.launch { source.stream.collect { (_, size) -> if (size > 0) samplesSeen += size } }
		if (OfflineEngines.isSherpa(model.engine)) {
			_display.value = LiveTranscriptState(status = LiveTranscriptStatus.LOADING, modelLabel = label)
			offlineEngine.load(modelId).onFailure { e ->
				Log.e(TAG, "Offline model load failed", e)
				_display.value = LiveTranscriptState(status = LiveTranscriptStatus.ERROR, message = "離線模型載入失敗：${e.message}", modelLabel = label)
				return
			}
			_display.value = LiveTranscriptState(status = LiveTranscriptStatus.LISTENING, modelLabel = label)
			val p = LiveUtterancePipeline(scope) { samples -> offlineEngine.decode(samples) }
			pipeline = p
			val offsetMs = samplesSeen * 1000 / 16_000
			scope.launch { p.timedLines.collect { list -> _timed.value = list.map { (ms, t) -> (ms + offsetMs) to t } } }
			activeModel = modelId to model.engine
			displayJob = scope.launch {
				combine(p.lines, p.decoding, p.warning) { lines, decoding, warning ->
					LiveTranscriptState(
						finalized = lines,
						status = if (decoding) LiveTranscriptStatus.DECODING else LiveTranscriptStatus.LISTENING,
						message = warning,
						modelLabel = label,
					)
				}.collect { _display.value = it }
			}
			collector = scope.launch {
				source.stream.collect { (buffer, size) -> if (size > 0) p.accept(buffer, size) }
			}
		} else {
			_display.value = LiveTranscriptState(status = LiveTranscriptStatus.LOADING, modelLabel = label)
			if (transcriptor.setUp(modelId).isFailure) {
				_display.value = LiveTranscriptState(status = LiveTranscriptStatus.ERROR, message = "Vosk 模型載入失敗", modelLabel = label)
				return
			}
			_display.value = LiveTranscriptState(status = LiveTranscriptStatus.LISTENING, modelLabel = label)
			activeModel = modelId to model.engine
			val queue = Channel<ShortArray>(capacity = 64)
			collector = scope.launch {
				source.stream.collect { (buffer, size) ->
					if (size <= 0) return@collect
					if (queue.trySend(buffer.copyOf(size)).isFailure) {
						voskDropped += size
						_display.value = _display.value.copy(message = "辨識跟不上，部分音訊未辨識（錄音完整保存）")
					}
				}
			}
			scope.launch {
				for (chunk in queue) {
					try {
						val r = transcriptor.recognizeAudio(chunk, chunk.size) ?: continue
						val finals = synchronized(voskFinals) {
							if (r.fullResult.isNotBlank()) voskFinals += r.fullResult
							voskFinals.toList()
						}
						_display.value = _display.value.copy(
							finalized = finals,
							partial = if (r.fullResult.isNotBlank()) "" else r.partial,
							status = LiveTranscriptStatus.LISTENING,
						)
					} catch (e: CancellationException) { throw e } catch (e: Exception) {
						Log.e(TAG, "VOSK ERROR", e)
						_display.value = _display.value.copy(message = "辨識錯誤：${e.message}")
					}
				}
			}
		}
	}

	override suspend fun finishTranscription(): LiveTranscript? {
		val (modelId, engine) = activeModel ?: return null
		collector?.cancel()
		val p = pipeline
		return if (p != null) {
			val complete = p.finish()
			LiveTranscript(modelId, engine, p.text(), p.decodedUtterances, p.failedUtterances, p.droppedSamples * 1000 / 16_000, complete && p.droppedSamples == 0L && p.failedUtterances == 0)
		} else {
			val text = synchronized(voskFinals) { voskFinals.joinToString("\n") }
			LiveTranscript(modelId, engine, text, voskFinals.size, 0, voskDropped * 1000 / 16_000, voskDropped == 0L)
		}
	}

	override fun transcriptCleanUp() {
		val usedOffline = pipeline != null
		pipeline?.cancel()
		scope.cancel()
		pipeline = null
		activeModel = null
		transcriptor.cleanUp()
		// free the large native model once any in-flight decode has returned
		if (usedOffline) CoroutineScope(Dispatchers.IO).launch { offlineEngine.release() }
	}
}
