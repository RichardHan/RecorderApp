package io.github.richardhan.voicenote

import android.os.SystemClock
import androidx.compose.runtime.*
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

private const val OPEN_ROUTER = "https://openrouter.ai/api/v1"
private data class SpeechResponse(val body: JSONObject, val gatewayCost: String?)
private fun JSONObject.costUsd() = reportedCostUsd(optJSONObject("usage")?.optString("cost"))
private class HttpFailure(val code: Int) : Exception()

class SpeechConnection internal constructor(app: Application, private val open: HttpsOpen) : AndroidViewModel(app) {
	constructor(app: Application) : this(app, ::openHttps)
	private val vault = ConnectionVault(app, "studio-speech")
	var custom by mutableStateOf(false)
		private set
	var base by mutableStateOf(OPEN_ROUTER)
		private set
	var key by mutableStateOf("")
	var model by mutableStateOf("openai/whisper-large-v3-turbo")
	var models by mutableStateOf(emptyList<String>())
		private set
	var busy by mutableStateOf(false)
		private set
	var error by mutableIntStateOf(0)
		private set
	var httpStatus by mutableIntStateOf(0)
		private set
	var revision by mutableIntStateOf(0)
		private set

	internal fun recognize(file: File, models: OfflineSpeechModels) = request {
		withContext(Dispatchers.IO) { models.recognize(file) }
		revision++
	}

	internal fun edit(file: File, text: String) = request {
		require(text.isNotBlank())
		withContext(Dispatchers.IO) { archiveTranscript(file); writeSidecar(transcriptFile(file), text) }
		revision++
	}

	var chatModel by mutableStateOf("")

	var hasSavedConnection by mutableStateOf(false)
		private set
	init {
		try {
			vault.load()?.let {
				custom = it.getBoolean("custom")
				base = it.getString("base")
				key = it.getString("key")
				model = it.getString("model")
				chatModel = it.optString("chatModel")
				hasSavedConnection = true
			}
		} catch (_: Exception) { error = R.string.connection_error }
	}
	fun saveConnection() = request {
		require(valid())
		val data = JSONObject().put("custom", custom).put("base", base).put("key", key).put("model", model).put("chatModel", chatModel)
		withContext(Dispatchers.IO) { vault.save(data) }
		hasSavedConnection = true
	}
	fun forgetConnection() = request {
		withContext(Dispatchers.IO) { vault.remove() }
		key = ""
		hasSavedConnection = false
	}

	internal fun ask(file: File, transcript: String, question: String) {
		val server = endpoint()
		if (server == null || key.isBlank() || chatModel.isBlank()) { error = R.string.invalid; return }
		val modelId = chatModel.trim()
		val token = key.trim()
		request {
			withContext(Dispatchers.IO) {
				val target = chatFile(file, transcript)
				val history = readMessages(target)
				val payload = chatPayload(modelId, transcript, history, question).toString().toByteArray()
				val response = exchange("$server/chat/completions", token) { conn ->
					conn.requestMethod = "POST"
					conn.doOutput = true
					conn.setRequestProperty("Content-Type", "application/json")
					conn.outputStream.use { it.write(payload) }
				}
				val answer = response.body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
				require(answer.isNotBlank())
				history.put(JSONObject().put("question", question).put("answer", answer).put("model", modelId))
				writeSidecar(target, history.toString())
			}
			revision++
		}
	}

	fun changeBase(value: String) {
		base = value
		key = ""
	}
	fun setCustomMode(value: Boolean) {
		custom = value
		changeBase(if (custom) "" else OPEN_ROUTER)
		model = if (custom) "" else "openai/whisper-large-v3-turbo"
		models = emptyList()
		error = 0
	}

	private fun endpoint(): URI? = runCatching { URI(base.trim().trimEnd('/')) }.getOrNull()?.takeIf {
		it.scheme.equals("https", ignoreCase = true) && it.host != null && it.userInfo == null &&
			it.query == null && it.fragment == null
	}
	fun valid(): Boolean = endpoint() != null && model.isNotBlank() && key.isNotBlank()

	private fun request(block: suspend () -> Unit) {
		if (busy) return
		busy = true
		error = 0
		viewModelScope.launch {
			try { block() }
			catch (e: CancellationException) { throw e }
			catch (_: LinkageError) { error = R.string.offline_error }
			catch (e: HttpFailure) { httpStatus = e.code; error = R.string.http_error }
			catch (_: Exception) { error = R.string.failed }
			finally { busy = false }
		}
	}

	fun loadModels() = request {
		models = withContext(Dispatchers.IO) {
			val response = exchange("$OPEN_ROUTER/models?output_modalities=transcription", "")
			val data = response.body.getJSONArray("data")
			(0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
		}
	}

	internal fun youtube(file: File, link: String, chosen: String) {
		val token = key.trim()
		if (custom || token.isEmpty() || chosen.isBlank()) { error = R.string.invalid; return }
		request {
			withContext(Dispatchers.IO) {
				val content = org.json.JSONArray()
					.put(JSONObject().put("type", "input_text").put("text", "Transcribe all speech verbatim in its original languages. Return only the transcript."))
					.put(JSONObject().put("type", "input_video").put("video_url", link))
				val input = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", content))
				val payload = JSONObject().put("model", chosen).put("input", input).toString().toByteArray()
				val started = SystemClock.elapsedRealtime()
				val response = exchange("$OPEN_ROUTER/responses", token) { conn ->
					conn.requestMethod = "POST"
					conn.doOutput = true
					conn.setRequestProperty("Content-Type", "application/json")
					conn.outputStream.use { it.write(payload) }
				}
				saveVideoTranscription(file, response.body, chosen, SystemClock.elapsedRealtime() - started)
			}
			revision++
		}
	}

	fun transcribe(file: File, timestamps: Boolean) {
		if (!valid()) { error = R.string.invalid; return }
		val url = "${endpoint()}/audio/transcriptions"
		val token = key.trim()
		val chosen = model.trim()
		val useGatewayCost = custom
		request {
			withContext(Dispatchers.IO) {
				val started = SystemClock.elapsedRealtime()
				val reply = exchange(url, token) { connection ->
					val boundary = UUID.randomUUID().toString()
					val format = if (timestamps) "--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\nverbose_json\r\n" else ""
					val header = format + "--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$chosen\r\n" +
						"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.${file.extension}\"\r\nContent-Type: ${audioTypes.getValue(file.extension)}\r\n\r\n"
					val prefix = header.toByteArray()
					val suffix = "\r\n--$boundary--\r\n".toByteArray()
					connection.requestMethod = "POST"
					connection.doOutput = true
					connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
					connection.setFixedLengthStreamingMode(prefix.size + file.length() + suffix.size)
					connection.outputStream.use { out ->
						out.write(prefix)
						file.inputStream().use { it.copyTo(out) }
						out.write(suffix)
					}
				}
				val elapsedMs = SystemClock.elapsedRealtime() - started
				val gatewayCost = if (useGatewayCost) reportedCostUsd(reply.gatewayCost) else null
				val cost = gatewayCost ?: reply.body.costUsd()
				val text = requireNotNull(reply.body.opt("text") as? String)
				val segments = if (timestamps) reply.body.optJSONArray("segments") else null
				saveTranscription(file, text, TranscriptionReceipt(chosen, elapsedMs, cost), segments)
			}
			revision++
		}
	}

	private fun exchange(url: String, token: String, send: (HttpsURLConnection) -> Unit = {}): SpeechResponse {
		val connection = open(URI(url).toURL())
		try {
			connection.instanceFollowRedirects = false
			connection.connectTimeout = 15000
			connection.readTimeout = 180000
			if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
			send(connection)
			if (connection.responseCode !in 200..299) throw HttpFailure(connection.responseCode)
			val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
			return SpeechResponse(body, connection.getHeaderField("x-litellm-response-cost"))
		} finally { connection.disconnect() }
	}
}
internal fun transcriptFile(file: File) = File(file.parentFile, file.nameWithoutExtension + ".txt")

internal fun saveVideoTranscription(file: File, response: JSONObject, model: String, elapsedMs: Long) {
	require(response.optString("status") == "completed")
	val output = response.getJSONArray("output")
	val text = buildString {
		for (i in 0 until output.length()) {
			val parts = output.getJSONObject(i).optJSONArray("content") ?: continue
			for (j in 0 until parts.length()) {
				val part = parts.getJSONObject(j)
				if (part.optString("type") == "output_text") appendLine(part.getString("text"))
			}
		}
	}.trim()
	saveTranscription(file, text, TranscriptionReceipt(model, elapsedMs, response.costUsd()))
}
