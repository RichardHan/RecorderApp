package io.github.richardhan.voicenote

import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal enum class SpeechProtocol(val label: String) { Multipart("Audio API"), Chat("Chat Completions"), Gemini("Gemini") }

internal fun pcmWave(samples: FloatArray): ByteArray {
	val size = samples.size * 2
	val data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
	data.put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
	data.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
	data.put("data".toByteArray()).putInt(size)
	for (sample in samples) data.putShort((sample * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
	return data.array()
}

internal fun audioPayload(protocol: SpeechProtocol, model: String, samples: FloatArray, vocabulary: String): JSONObject {
	val data = Base64.encodeToString(pcmWave(samples), Base64.NO_WRAP)
	val prompt = "Transcribe this audio in its original language. Return only the spoken words." +
		if (vocabulary.isBlank()) "" else "\nPreferred names and spellings:\n$vocabulary"
	return when (protocol) {
		SpeechProtocol.Chat -> {
			val parts = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
				.put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject().put("format", "wav").put("data", data)))
			JSONObject().put("model", model).put("stream", false).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", parts)))
		}
		SpeechProtocol.Gemini -> {
			val parts = JSONArray().put(JSONObject().put("text", prompt)).put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", data)))
			JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
		}
		else -> error("Multipart uses a file upload")
	}
}

internal fun generatedText(protocol: SpeechProtocol, body: JSONObject): String {
	val answer = if (protocol == SpeechProtocol.Gemini) {
		val parts = body.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
		(0 until parts.length()).map { parts.getJSONObject(it) }.filterNot { it.optBoolean("thought") }.joinToString("") { it.optString("text") }
	} else body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
	return answer.trim().also { require(it.isNotBlank()) }
}

internal suspend fun segmentedTranscript(file: File, endpoint: String, model: String, protocol: SpeechProtocol, vocabulary: String,
	seconds: Int, concurrent: Int, skipSilence: Boolean, effort: String?, request: (JSONObject) -> JSONObject) = supervisorScope {
	require(seconds in 10..300 && concurrent in 1..6)
	val identity = (listOf(endpoint, model, protocol.name, vocabulary, seconds, skipSilence, file.length(), file.lastModified()) + listOfNotNull(effort)).joinToString("\u0000")
	val checkpoint = File(file.parentFile, file.name + "." + transcriptDigest(identity).toHexString() + ".checkpoint")
	val completed = if (checkpoint.exists()) JSONArray(checkpoint.readText()) else JSONArray()
	// Each in-flight request holds PCM, a WAV copy and base64 JSON until the HTTP write completes.
	val bytesPerRequest = seconds * 16000L * 24
	val heapLimit = (Runtime.getRuntime().maxMemory() / 2 / bytesPerRequest).toInt().coerceAtLeast(1)
	val slots = Semaphore(minOf(concurrent, heapLimit))
	val tasks = mutableListOf<Job>()
	val failure = AtomicReference<Exception?>(null)
	var index = 0
	var offset = 0L
	val began = android.os.SystemClock.elapsedRealtime()
	suspend fun segment(samples: FloatArray) {
		currentCoroutineContext().ensureActive()
		val part = index++
		val start = offset / 16000.0
		offset += samples.size
		val end = offset / 16000.0
		failure.get()?.let { throw it }
		if (synchronized(completed) { completed.optJSONObject(part) != null }) return
		slots.acquire()
		failure.get()?.let { slots.release(); throw it }
		tasks.add(launch(Dispatchers.IO) {
			try {
				val row = JSONObject().put("start", start).put("end", end)
				if (skipSilence && samples.all { kotlin.math.abs(it) < 0.001f }) {
					row.put("text", "[silence]").put("cost", "0").put("tokens", 0).put("requests", 0)
				} else {
					val reply = request(audioPayload(protocol, model, samples, vocabulary))
					val usage = reply.optJSONObject("usage") ?: reply.optJSONObject("usageMetadata")
					row.put("text", generatedText(protocol, reply))
						.put("cost", reportedCostUsd(usage?.optString("cost")) ?: JSONObject.NULL)
						.put("tokens", usage?.optLong("total_tokens", usage.optLong("totalTokenCount", -1)) ?: -1).put("requests", 1)
				}
				synchronized(completed) {
					completed.put(part, row)
					writeSidecar(checkpoint, completed.toString())
				}
			} catch (e: CancellationException) { throw e }
			catch (e: Exception) { failure.compareAndSet(null, e) }
			finally { slots.release() }
		})
	}
	try { segment(decodeForSpeakers(file, ::segment, seconds)) }
	catch (e: CancellationException) { throw e }
	catch (e: Exception) { failure.compareAndSet(null, e) }
	tasks.joinAll()
	failure.get()?.let { throw it }
	currentCoroutineContext().ensureActive()
	require(index == completed.length())
	val rows = (0 until index).map(completed::getJSONObject)
	val costs = rows.map { reportedCostUsd(it.optString("cost"))?.toBigDecimalOrNull() }
	val tokens = rows.map { it.optLong("tokens", -1) }.takeIf { values -> values.all { it >= 0 } }?.sum()
	val totalCost = if (costs.all { it != null }) costs.filterNotNull().sumOf { it }.toPlainString() else null
	saveTranscription(file, rows.joinToString("\n") { it.getString("text") }, TranscriptionReceipt(model, android.os.SystemClock.elapsedRealtime() - began, totalCost, tokens, rows.sumOf { it.optInt("requests", 1) }, java.net.URI(endpoint).host), completed)
	check(!checkpoint.exists() || checkpoint.delete())
}
