package com.eva.cloudtranscribe.api

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String)

/** Abstraction so tests never hit the network. */
fun interface HttpTransport {
	@Throws(IOException::class)
	fun postJson(url: String, headers: Map<String, String>, body: String, timeoutSeconds: Int): HttpResponse
}

class UrlConnectionTransport : HttpTransport {
	override fun postJson(url: String, headers: Map<String, String>, body: String, timeoutSeconds: Int): HttpResponse {
		require(url.startsWith("https://", ignoreCase = true)) { "Only https endpoints are allowed" }
		val connection = (URL(url).openConnection() as HttpURLConnection).apply {
			requestMethod = "POST"
			doOutput = true
			connectTimeout = 30_000
			readTimeout = timeoutSeconds * 1000
			setRequestProperty("Content-Type", "application/json")
			setRequestProperty("Accept", "application/json")
			headers.forEach { (k, v) -> setRequestProperty(k, v) }
		}
		try {
			val bytes = body.toByteArray(Charsets.UTF_8)
			connection.setFixedLengthStreamingMode(bytes.size)
			connection.outputStream.use { it.write(bytes) }
			val code = connection.responseCode
			val stream = if (code in 200..299) connection.inputStream else connection.errorStream
			val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			return HttpResponse(code, text)
		} finally {
			connection.disconnect()
		}
	}
}

object Redactor {
	/** Removes the secret and anything that looks like a bearer token from text kept in logs/results. */
	fun redact(text: String?, secret: String?): String {
		var out = text.orEmpty()
		if (!secret.isNullOrBlank()) out = out.replace(secret, "***")
		out = out.replace(Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+"), "Bearer ***")
		out = out.replace(Regex("(?i)(key=)[A-Za-z0-9._\\-]+"), "$1***")
		return if (out.length > 300) out.take(300) + "…" else out
	}

	fun hostOf(url: String): String? = runCatching { URL(url.trim()).host }.getOrNull()
}
