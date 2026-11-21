package io.github.richardhan.voicenote

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
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
private class HttpFailure(val code: Int) : Exception()

class SpeechConnection : ViewModel() {
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

	fun youtube(file: File, chosen: String) {
		val token = key.trim()
		if (custom || token.isEmpty() || chosen.isBlank()) { error = R.string.invalid; return }
		request {
			withContext(Dispatchers.IO) {
				val content = org.json.JSONArray()
					.put(JSONObject().put("type", "input_text").put("text", "Transcribe all speech verbatim in its original languages. Return only the transcript."))
					.put(JSONObject().put("type", "input_video").put("video_url", file.readText()))
				val input = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", content))
				val payload = JSONObject().put("model", chosen).put("input", input).toString().toByteArray()
				val response = exchange("$OPEN_ROUTER/responses", token) { conn ->
					conn.requestMethod = "POST"
					conn.doOutput = true
					conn.setRequestProperty("Content-Type", "application/json")
					conn.outputStream.use { it.write(payload) }
				}
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
				storeTranscript(file, text)
			}
			revision++
		}
	}

	private fun storeTranscript(file: File, text: String) {
		require(text.isNotBlank())
		val destination = transcriptFile(file)
		val temp = File.createTempFile("transcript-", ".part", destination.parentFile)
		try { temp.writeText(text); check(temp.renameTo(destination)) } finally { temp.delete() }
	}

	fun transcribe(file: File) {
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
					val header = "--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$chosen\r\n" +
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
				val cost = gatewayCost ?: reportedCostUsd(reply.body.optJSONObject("usage")?.optString("cost"))
				val text = requireNotNull(reply.body.opt("text") as? String)
				saveTranscription(file, text, TranscriptionReceipt(chosen, elapsedMs, cost))
			}
			revision++
		}
	}

	private fun exchange(url: String, token: String, send: (HttpsURLConnection) -> Unit = {}): SpeechResponse {
		val connection = URI(url).toURL().openConnection() as HttpsURLConnection
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
