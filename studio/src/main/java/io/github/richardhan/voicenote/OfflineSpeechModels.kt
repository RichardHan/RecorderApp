package io.github.richardhan.voicenote

import android.app.Application
import android.os.SystemClock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL

private const val speechAssets = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"

class OfflineSpeechModels(app: Application) : AndroidViewModel(app) {
	private val dir = File(app.noBackupFilesDir, "sense-voice-v1").apply { mkdirs() }
	private val network = File(dir, "model.int8.onnx")
	private val tokens = File(dir, "tokens.txt")
	private val modelDownload = ModelDownload(URL("$speechAssets/model.int8.onnx"), network, 239233841,
		"c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51")
	private val tokenDownload = ModelDownload(URL("$speechAssets/tokens.txt"), tokens, 315894,
		"f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc")

	private fun available() = network.length() == modelDownload.expectedBytes && tokens.length() == tokenDownload.expectedBytes
	var installed by mutableStateOf(available())
		private set
	var busy by mutableStateOf(false)
		private set
	var failed by mutableStateOf(false)
		private set
	var progress by mutableLongStateOf(0L)
		private set
	private var job: Job? = null

	fun changeInstallation() {
		if (busy) return
		val remove = installed
		busy = true
		failed = false
		job = viewModelScope.launch {
			try {
				withContext(Dispatchers.IO) {
					if (remove) {
						tokenDownload.uninstall()
						modelDownload.uninstall()
					} else {
						tokenDownload.install {}
						modelDownload.install { bytes -> withContext(Dispatchers.Main) { progress = bytes * 100 / modelDownload.expectedBytes } }
					}
				}
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { failed = true }
			finally { installed = available(); busy = false }
		}
	}

	fun pause() { job?.cancel() }

	internal fun recognizer(): OfflineRecognizer {
		check(available())
		val config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
			senseVoice = OfflineSenseVoiceModelConfig(model = network.path, language = "auto", useInverseTextNormalization = true),
			tokens = tokens.path, numThreads = 2, provider = "cpu"))
		return OfflineRecognizer(null, config)
	}

	internal suspend fun recognize(file: File) {
		val started = SystemClock.elapsedRealtime()
		val recognizer = recognizer()
		try {
			val segments = JSONArray()
			val words = mutableListOf<String>()
			var samplesSeen = 0L
			val task = currentCoroutineContext()
			fun transcribe(samples: FloatArray) {
				task.ensureActive()
				val text = recognizeChunk(recognizer, samples)
				val start = samplesSeen / 16000.0
				samplesSeen += samples.size
				if (text.isNotBlank()) {
					words.add(text)
					segments.put(JSONObject().put("start", start).put("end", samplesSeen / 16000.0).put("text", text))
				}
			}
			transcribe(decodeForSpeakers(file, ::transcribe))
			task.ensureActive()
			saveTranscription(file, words.joinToString("\n"), TranscriptionReceipt("SenseVoice · offline", SystemClock.elapsedRealtime() - started, "0"), segments)
		} finally { recognizer.release() }
	}
}

internal fun recognizeChunk(recognizer: OfflineRecognizer, samples: FloatArray): String {
	val stream = recognizer.createStream()
	return try {
		stream.acceptWaveform(samples, 16000)
		recognizer.decode(stream)
		recognizer.getResult(stream).text.trim()
	} finally { stream.release() }
}

@Composable
internal fun OfflineModelPanel(models: OfflineSpeechModels, transcribing: Boolean) {
	if (models.busy) {
		Text(stringResource(R.string.download_percent, models.progress.toInt()))
		TextButton(onClick = models::pause) { Text(stringResource(R.string.pause_model)) }
	} else TextButton(enabled = !transcribing, onClick = models::changeInstallation) {
		Text(stringResource(if (models.installed) R.string.remove_asr else R.string.download_asr))
	}
	if (models.failed) Text(stringResource(R.string.offline_error))
}
