package com.eva.cloudtranscribe.api

import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.ProviderType
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.io.IOException

/** One explicit text-only request via the same adapters as transcription. No audio, retries or fallback. */
object ConnectionProbe {
    const val TOKEN_LIMIT = 16
    const val TIMEOUT_SECONDS = 15
    fun run(settings: CloudSettings, key: String?, transport: HttpTransport): String {
        if (key.isNullOrBlank()) return "尚未設定 API Key，請先儲存 Key。"
        val uri = runCatching { URI(settings.baseUrl.trim()) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null)
            return "請先儲存有效的 HTTPS Endpoint（不可含帳密、查詢參數或片段）。"
        if (settings.transcribeModel.isBlank()) return "請先儲存轉錄模型名稱。"
        var transportError: String? = null
        var unsupported = false
        var calls = 0
        val guarded = HttpTransport { url, headers, body, timeout ->
            check(++calls == 1) { "Probe must not retry" }
            try {
                transport.postJson(url, headers, body, timeout).also { response ->
                    unsupported = response.code in listOf(400, 422) &&
                        listOf("reasoning_effort", "unsupported", "model", "not support").any { response.body.contains(it, true) }
                }
            } catch (e: IOException) {
                transportError = when (e) {
                    is SocketTimeoutException -> "連線逾時（15 秒）；未重試。"
                    is SSLException -> "TLS／憑證驗證失敗；未重試。"
                    else -> "網路連線失敗；請檢查網路與 Endpoint。未重試。"
                }
                // Deliberately discard exception text (may contain URL credentials or server secrets).
                throw IOException("Connection probe network failure")
            }
        }
        val start = System.nanoTime()
        val outcome = try {
            val client: CloudAiClient = when (settings.providerType) {
                ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(settings.baseUrl, key, settings.transcribeModel, settings.reasoningEffort, guarded, TOKEN_LIMIT, TIMEOUT_SECONDS)
                ProviderType.GEMINI_NATIVE -> GeminiNativeClient(settings.baseUrl, key, settings.transcribeModel, guarded, TOKEN_LIMIT, TIMEOUT_SECONDS)
            }
            client.complete("Reply with OK only.")
        } catch (e: Exception) { return "測試失敗：設定或回應格式錯誤；未重試。" }
        transportError?.let { return it }
        val ms = (System.nanoTime() - start) / 1_000_000
        val result = when (outcome) {
            is CallOutcome.Text -> if (outcome.text.isNotBlank() && !outcome.text.trimStart().startsWith("<"))
                "連線成功 · ${ms} ms · ${settings.providerType} · ${uri.host} · ${Redactor.redact(settings.transcribeModel, key)}" else "服務回傳空白或非文字 API 回應，測試未通過。"
            is CallOutcome.Failure -> when (outcome.httpCode) {
                401, 403 -> "HTTP ${outcome.httpCode}：API Key 或存取權限錯誤。"
                404 -> "HTTP 404：Endpoint 路徑或模型不存在。"
                429 -> "HTTP 429：配額不足或請求頻率限制；未重試。"
                400, 422 -> if (unsupported) "HTTP ${outcome.httpCode}：模型或 reasoning_effort 可能不受支援；請檢查設定。" else "HTTP ${outcome.httpCode}：請求參數不受服務接受。"
                null -> "回應格式／設定錯誤，測試未通過。"
                else -> "HTTP ${outcome.httpCode}：服務未接受請求；未重試。"
            }
            else -> "服務未回傳有效文字（空白或被過濾），測試未通過。"
        }
        return "$result\n僅文字連線測試，不代表支援音訊；沒有上傳錄音。"
    }
}

/** Probe-only, bounded, cancellable HTTP transport. Never follows redirects (avoids credential forwarding/replays). */
class ProbeTransport : HttpTransport {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; connection?.disconnect() }
    override fun postJson(url: String, headers: Map<String, String>, body: String, timeoutSeconds: Int): HttpResponse {
        if (cancelled) throw IOException("Cancelled")
        val c = (URL(url).openConnection() as HttpURLConnection)
        connection = c
        try {
            if (cancelled) throw IOException("Cancelled")
            c.instanceFollowRedirects = false
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = timeoutSeconds * 1000; c.readTimeout = timeoutSeconds * 1000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val response = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    if (cancelled) throw IOException("Cancelled")
                    val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > 65536) throw IOException("Response too large")
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }.orEmpty()
            return HttpResponse(code, response)
        } finally { c.disconnect(); connection = null }
    }
}
