package com.eva.cloudtranscribe.slack

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.sync.withLock

data class SlackHttpResponse(val code: Int, val body: String, val retryAfter: String?)

/** Abstraction so tests never reach Slack. */
fun interface SlackTransport {
	@Throws(IOException::class)
	fun post(webhookUrl: String, jsonBody: String): SlackHttpResponse
}

class UrlConnectionSlackTransport : SlackTransport {
	override fun post(webhookUrl: String, jsonBody: String): SlackHttpResponse {
		require(SlackMessages.isValidWebhookUrl(webhookUrl)) { "Not a Slack incoming webhook" }
		val connection = (URL(webhookUrl.trim()).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			doOutput = true
			connectTimeout = 15_000
			readTimeout = 20_000
			instanceFollowRedirects = false
			setRequestProperty("Content-Type", "application/json; charset=utf-8")
		}
		try {
			val bytes = jsonBody.toByteArray(Charsets.UTF_8)
			connection.setFixedLengthStreamingMode(bytes.size)
			connection.outputStream.use { it.write(bytes) }
			val code = connection.responseCode
			val stream = if (code in 200..299) connection.inputStream else connection.errorStream
			val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			return SlackHttpResponse(code, text, connection.getHeaderField("Retry-After"))
		} finally {
			connection.disconnect()
		}
	}
}

/**
 * Sends one message. Keeps at least [minSpacingMillis] between posts in this process (Slack: ~1 message per second
 * per webhook / channel) and honours a short `Retry-After` inline once.
 */
class SlackSender(
	private val transport: SlackTransport,
	private val clock: () -> Long = { System.currentTimeMillis() },
	private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
	private val minSpacingMillis: Long = 1_100L,
	private val maxInlineWaitSeconds: Long = 60L,
) {
	private val mutex = kotlinx.coroutines.sync.Mutex()
	private var lastPostAt: Long? = null

	suspend fun send(webhookUrl: String, text: String): SlackSendResult = mutex.withLock {
		var result = postOnce(webhookUrl, text)
		if (result is SlackSendResult.RetryAfter && result.seconds <= maxInlineWaitSeconds) {
			sleep(result.seconds * 1_000L)
			result = postOnce(webhookUrl, text)
		}
		result
	}

	private suspend fun postOnce(webhookUrl: String, text: String): SlackSendResult {
		lastPostAt?.let { last ->
			val wait = last + minSpacingMillis - clock()
			if (wait > 0) sleep(wait)
		}
		return try {
			val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
				transport.post(webhookUrl, SlackMessages.payload(text))
			}
			SlackSendPolicy.classify(response.code, response.body, response.retryAfter)
		} catch (e: IOException) {
			SlackSendResult.Retry("網路錯誤")
		} catch (e: IllegalArgumentException) {
			SlackSendResult.Permanent("Webhook 網址無效")
		} finally {
			lastPostAt = clock()
		}
	}
}
