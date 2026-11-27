package io.github.richardhan.voicenote

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

internal data class ModelAsset(val name: String, val size: Long, val hash: String)
internal data class ModelEntry(val id: String, val label: String, val base: String, val files: List<ModelAsset>) {
	val vosk get() = id.startsWith("vosk-model-")
	val bytes get() = files.sumOf { it.size }
	fun directory(context: Context) = File(context.noBackupFilesDir, id)
	fun installed(context: Context): Boolean {
		val root = directory(context)
		return if (vosk) File(root, "installed/am/final.mdl").isFile
		else files.all { File(root, it.name).length() == it.size }
	}

	suspend fun install(context: Context, progress: suspend (Long) -> Unit) {
		val root = directory(context).apply { mkdirs() }
		var done = 0L
		val remaining = files.sumOf { spec ->
			val target = File(root, spec.name)
			val partial = File(target.parentFile, target.name + ".part")
			if (target.length() == spec.size) 0 else spec.size - partial.length().coerceIn(0, spec.size)
		}
		val reserve = if (id == "qwen-large") 3500L * 1024 * 1024 else 200L * 1024 * 1024
		check(root.usableSpace > remaining + reserve + (if (vosk) bytes * 3 else 0))
		for (spec in files) {
			val target = File(root, spec.name)
			target.parentFile?.mkdirs()
			val fetch = ModelDownload(URL(base + spec.name), target, spec.size, spec.hash, if (vosk) "MD5" else "SHA-256")
			fetch.install { n -> progress((done + n) * 100 / bytes) }
			done += spec.size
			progress(done * 100 / bytes)
		}
		if (vosk) unpackVosk(root, File(root, files.single().name))
	}

	fun decoder(context: Context): SpeechDecoder {
		val root = directory(context)
		check(installed(context))
		if (vosk) {
			val model = Model(File(root, "installed").path)
			return object : SpeechDecoder {
				override fun words(samples: FloatArray): String = Recognizer(model, 16000f).use { speech ->
					val pcm = ShortArray(samples.size) { (samples[it] * 32768).toInt().coerceIn(-32768, 32767).toShort() }
					val phrases = mutableListOf<String>()
					var index = 0
					while (index < pcm.size) {
						val part = pcm.copyOfRange(index, minOf(index + 4000, pcm.size))
						if (speech.acceptWaveForm(part, part.size)) phrases.add(JSONObject(speech.result).optString("text"))
						index += part.size
					}
					phrases.add(JSONObject(speech.finalResult).optString("text"))
					phrases.filter(String::isNotBlank).joinToString(" ")
				}
				override fun close() = model.close()
			}
		}
		val ram = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
		check(!ram.lowMemory && ram.availMem > bytes * 13 / 10 + 512L * 1024 * 1024 && (bytes < 1500000000 || Process.is64Bit()))
		fun path(name: String) = File(root, name).path
		val config = OfflineModelConfig(numThreads = 2, provider = "cpu")
		val features = FeatureConfig()
		when {
			id.startsWith("qwen-") -> {
				features.featureDim = 128
				config.qwen3Asr = OfflineQwen3AsrModelConfig(convFrontend = path("conv_frontend.onnx"), encoder = path("encoder.int8.onnx"),
					decoder = path("decoder.int8.onnx"), tokenizer = path("tokenizer"), maxTotalLen = 512, maxNewTokens = 192)
			}
			id == "firered" -> {
				config.fireRedAsr = OfflineFireRedAsrModelConfig(encoder = path("encoder.int8.onnx"), decoder = path("decoder.int8.onnx"))
				config.tokens = path("tokens.txt")
			}
			else -> {
				config.senseVoice = OfflineSenseVoiceModelConfig(model = path("model.int8.onnx"), language = "auto", useInverseTextNormalization = true)
				config.tokens = path("tokens.txt")
			}
		}
		val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig(featConfig = features, modelConfig = config))
		return object : SpeechDecoder {
			override fun words(samples: FloatArray): String {
				val stream = recognizer.createStream()
				return try {
					stream.acceptWaveform(samples, 16000)
					recognizer.decode(stream)
					val result = recognizer.getResult(stream)
					val text = result.text.trim().substringAfter("<asr_text>").trim()
					if (id.startsWith("qwen-") && result.tokens.size >= 192) "$text […]" else text
				} finally { stream.release() }
			}
			override fun close() = recognizer.release()
		}
	}
}

internal interface SpeechDecoder : Closeable { fun words(samples: FloatArray): String }

internal fun speechModels(context: Context): List<ModelEntry> {
	val entries = JSONArray(context.assets.open("asr-models.json").bufferedReader().use { it.readText() })
	return List(entries.length()) { i ->
		val entry = entries.getJSONObject(i)
		val assets = entry.getJSONArray("files")
		ModelEntry(entry.getString("id"), entry.getString("label"), entry.getString("base"), List(assets.length()) { j ->
			val item = assets.getJSONObject(j)
			ModelAsset(item.getString("name"), item.getLong("size"), item.getString("hash"))
		})
	}
}

private suspend fun unpackVosk(root: File, zip: File) {
	val staging = File(root, "unpacking")
	if (staging.exists()) check(staging.deleteRecursively())
	check(staging.mkdirs())
	try {
		ZipInputStream(zip.inputStream().buffered()).use { input ->
			var total = 0L
			val buffer = ByteArray(65536)
			while (true) {
				val entry = input.nextEntry ?: break
				val path = entry.name.substringAfter('/', "")
				if (entry.isDirectory || path.isBlank()) continue
				val target = File(staging, path).canonicalFile
				require(target.path.startsWith(staging.canonicalPath + File.separator))
				target.parentFile?.mkdirs()
				target.outputStream().use { out ->
					while (true) {
						currentCoroutineContext().ensureActive()
						val n = input.read(buffer)
						if (n < 0) break
						total += n
						require(total <= zip.length() * 8)
						out.write(buffer, 0, n)
					}
				}
			}
		}
		check(File(staging, "am/final.mdl").isFile && staging.renameTo(File(root, "installed")))
		zip.delete()
	} finally { if (staging.exists()) staging.deleteRecursively() }
}
