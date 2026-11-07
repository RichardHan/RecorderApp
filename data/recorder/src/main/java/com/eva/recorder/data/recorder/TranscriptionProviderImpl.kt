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
	private val _display = MutableStateFlow("")
	override val transcription: Flow<String> = _display.asStateFlow()

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
			return
		}
		val model = repository.readModelByLanguage(modelId).getOrElse {
			Log.w(TAG, "Selected model missing", it); return
		}
		if (OfflineEngines.isSherpa(model.engine)) {
			_display.value = "（載入離線模型…）"
			offlineEngine.load(modelId).onFailure { e ->
				Log.e(TAG, "Offline model load failed", e)
				_display.value = "⚠ 離線模型載入失敗：${e.message}"
				return
			}
			_display.value = ""
			val p = LiveUtterancePipeline(scope) { samples -> offlineEngine.decode(samples) }
			pipeline = p
			activeModel = modelId to model.engine
			displayJob = scope.launch { p.display.collect { _display.value = it } }
			collector = scope.launch {
				source.stream.collect { (buffer, size) -> if (size > 0) p.accept(buffer, size) }
			}
		} else {
			if (transcriptor.setUp(modelId).isFailure) return
			activeModel = modelId to model.engine
			val queue = Channel<ShortArray>(capacity = 64)
			collector = scope.launch {
				source.stream.collect { (buffer, size) ->
					if (size <= 0) return@collect
					if (queue.trySend(buffer.copyOf(size)).isFailure) {
						voskDropped += size
						_display.value = "⚠ 辨識跟不上，部分音訊未辨識"
					}
				}
			}
			scope.launch {
				for (chunk in queue) {
					try {
						val r = transcriptor.recognizeAudio(chunk, chunk.size) ?: continue
						if (r.fullResult.isNotBlank()) synchronized(voskFinals) { voskFinals += r.fullResult }
						_display.value = r.partial.ifBlank { r.fullResult }
					} catch (e: CancellationException) { throw e } catch (e: Exception) {
						Log.e(TAG, "VOSK ERROR", e)
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
