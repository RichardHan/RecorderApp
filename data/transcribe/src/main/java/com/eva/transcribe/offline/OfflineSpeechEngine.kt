package com.eva.transcribe.offline

import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.eva.transcribe.domain.models.STTModel
import com.eva.transcribe.domain.models.STTModelState
import com.eva.transcribe.domain.repository.STTModelsRepository
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "OFFLINE_SPEECH_ENGINE"

object OfflineEngines {
	const val SENSEVOICE = "sherpa-sensevoice"
	const val QWEN3 = "sherpa-qwen3-asr"
	const val FIRERED_AED = "sherpa-firered-aed"
	val ALL = setOf(SENSEVOICE, QWEN3, FIRERED_AED)
	fun isSherpa(engine: String?) = engine in ALL
}

/**
 * Holds at most ONE sherpa-onnx OfflineRecognizer (these models are 0.2–1.2 GB in RAM).
 * All native calls run under [lock]; release happens only after an in-flight decode returns,
 * so native memory is never freed under a running decode.
 */
@Singleton
class OfflineSpeechEngine @Inject constructor(
	private val repository: STTModelsRepository,
) {
	private val lock = Mutex()
	private var recognizer: OfflineRecognizer? = null
	private var loadedModelId: String? = null
	private var users = 0

	val currentModelId: String? get() = loadedModelId

	/** Loads [modelId] (unloading any other model first). */
	suspend fun load(modelId: String, numThreads: Int = 2): Result<STTModel> = withContext(Dispatchers.IO) {
		lock.withLock {
			runCatching {
				val model = repository.readModelByLanguage(modelId).getOrThrow()
				check(OfflineEngines.isSherpa(model.engine)) { "Not a sherpa-onnx model: ${model.engine}" }
				check(model.state == STTModelState.DOWNLOADED || model.state == STTModelState.UPDATE_AVAILABLE) { "模型尚未下載" }
				if (loadedModelId == modelId && recognizer != null) { users++; return@runCatching model }
				check(users == 0) { "另一個離線模型（$loadedModelId）正在使用中，請先停止錄音或其他離線轉錄" }
				releaseLocked()
				val dir = model.localModelURI?.toUri()?.toFile() ?: error("模型路徑不存在")
				val t = System.currentTimeMillis()
				recognizer = OfflineRecognizer(config = buildConfig(model.engine, dir, numThreads))
				loadedModelId = modelId
				users = 1
				Log.i(TAG, "Loaded ${model.engine} in ${System.currentTimeMillis() - t} ms")
				model
			}
		}
	}

	/** Decodes one utterance (16 kHz mono float samples). Waits for any other decode/load. */
	suspend fun decode(samples: FloatArray): String = withContext(Dispatchers.Default) {
		lock.withLock {
			val r = recognizer ?: error("離線模型未載入")
			val stream = r.createStream()
			try {
				stream.acceptWaveform(samples, 16_000)
				r.decode(stream)
				r.getResult(stream).text.trim()
			} finally {
				stream.release()
			}
		}
	}

	/** Each successful [load] must be paired with one release; native memory is freed when the last user leaves. */
	suspend fun release() = withContext(NonCancellable + Dispatchers.IO) {
		lock.withLock {
			if (users > 0) users--
			if (users == 0) releaseLocked()
		}
	}

	private fun releaseLocked() {
		recognizer?.release()
		recognizer = null
		if (loadedModelId != null) Log.i(TAG, "Released $loadedModelId")
		loadedModelId = null
	}

	companion object {
		fun buildConfig(engine: String, dir: File, numThreads: Int): OfflineRecognizerConfig {
			fun p(name: String) = File(dir, name).also { require(it.exists()) { "缺少模型檔 ${it.name}" } }.absolutePath
			val modelConfig = when (engine) {
				OfflineEngines.SENSEVOICE -> OfflineModelConfig(
					senseVoice = OfflineSenseVoiceModelConfig(model = p("model.int8.onnx"), language = "auto", useInverseTextNormalization = true),
					tokens = p("tokens.txt"), numThreads = numThreads, provider = "cpu", debug = false,
				)
				OfflineEngines.QWEN3 -> OfflineModelConfig(
					qwen3Asr = OfflineQwen3AsrModelConfig(
						convFrontend = p("conv_frontend.onnx"), encoder = p("encoder.int8.onnx"), decoder = p("decoder.int8.onnx"),
						tokenizer = p("tokenizer"), maxTotalLen = 512, maxNewTokens = 128, temperature = 1e-6f, topP = 0.8f, seed = 42,
					),
					numThreads = numThreads, provider = "cpu", debug = false,
				)
				OfflineEngines.FIRERED_AED -> OfflineModelConfig(
					fireRedAsr = OfflineFireRedAsrModelConfig(encoder = p("encoder.int8.onnx"), decoder = p("decoder.int8.onnx")),
					tokens = p("tokens.txt"), numThreads = numThreads, provider = "cpu", debug = false,
				)
				else -> error("Unsupported engine $engine")
			}
			val feat = if (engine == OfflineEngines.QWEN3) FeatureConfig(sampleRate = 16_000, featureDim = 128) else FeatureConfig(sampleRate = 16_000, featureDim = 80)
			return OfflineRecognizerConfig(featConfig = feat, modelConfig = modelConfig, decodingMethod = "greedy_search")
		}
	}
}
