package com.eva.cloudtranscribe.api

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings

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
        if (key.isNullOrBlank()) return UiStrings.get(StringsRes.string.data_cloudtranscribe_save_an_api_key_first, "尚未設定 API Key，請先儲存 Key。")
        val uri = runCatching { URI(settings.baseUrl.trim()) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null)
            return UiStrings.get(StringsRes.string.data_cloudtranscribe_save_a_valid_https_endpoint_without_credentials, "請先儲存有效的 HTTPS Endpoint（不可含帳密、查詢參數或片段）。")
        if (settings.transcribeModel.isBlank()) return UiStrings.get(StringsRes.string.data_cloudtranscribe_save_a_transcription_model_first, "請先儲存轉錄模型名稱。")
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
                    is SocketTimeoutException -> UiStrings.get(StringsRes.string.data_cloudtranscribe_connection_timed_out_after_15_seconds_no, "連線逾時（15 秒）；未重試。")
                    is SSLException -> UiStrings.get(StringsRes.string.data_cloudtranscribe_tls_or_certificate_verification_failed_no_retry, "TLS／憑證驗證失敗；未重試。")
                    else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_connection_failed_check_the_network_and_endpoint, "網路連線失敗；請檢查網路與 Endpoint。未重試。")
                }
                // Deliberately discard exception text (may contain URL credentials or server secrets).
                throw IOException("Connection probe network failure")
            }
        }
        val start = System.nanoTime()
        val outcome = try {
            val client: CloudAiClient = when (settings.providerType) {
                ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(settings.baseUrl, key, settings.transcribeModel, settings.transcriptionEffort, guarded, TOKEN_LIMIT, TIMEOUT_SECONDS)
                ProviderType.GEMINI_NATIVE -> GeminiNativeClient(settings.baseUrl, key, settings.transcribeModel, guarded, TOKEN_LIMIT, TIMEOUT_SECONDS)
            }
            client.complete("Reply with OK only.")
        } catch (e: Exception) { return UiStrings.get(StringsRes.string.data_cloudtranscribe_test_failed_check_the_settings_and_response, "測試失敗：設定或回應格式錯誤；未重試。") }
        transportError?.let { return it }
        val ms = (System.nanoTime() - start) / 1_000_000
        val result = when (outcome) {
            is CallOutcome.Text -> if (outcome.text.isNotBlank() && !outcome.text.trimStart().startsWith("<"))
                UiStrings.get(StringsRes.string.data_cloudtranscribe_connected_ms, "連線成功 · %1\$s ms · %2\$s · %3\$s · %4\$s", ms, settings.providerType, uri.host, Redactor.redact(settings.transcribeModel, key)) else UiStrings.get(StringsRes.string.data_cloudtranscribe_the_service_returned_an_empty_or_non, "服務回傳空白或非文字 API 回應，測試未通過。")
            is CallOutcome.Failure -> when (outcome.httpCode) {
                401, 403 -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_check_your_api_key_and_permissions, "HTTP %1\$s：API Key 或存取權限錯誤。", outcome.httpCode)
                404 -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_404_endpoint_or_model_not_found, "HTTP 404：Endpoint 路徑或模型不存在。")
                429 -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_429_quota_or_rate_limit_reached, "HTTP 429：配額不足或請求頻率限制；未重試。")
                400, 422 -> if (unsupported) UiStrings.get(StringsRes.string.data_cloudtranscribe_http_check_model_and_reasoning_effort_support, "HTTP %1\$s：模型或 reasoning_effort 可能不受支援；請檢查設定。", outcome.httpCode) else UiStrings.get(StringsRes.string.data_cloudtranscribe_http_the_service_rejected_the_request_parameters, "HTTP %1\$s：請求參數不受服務接受。", outcome.httpCode)
                null -> UiStrings.get(StringsRes.string.data_cloudtranscribe_test_failed_invalid_response_or_configuration, "回應格式／設定錯誤，測試未通過。")
                else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_request_rejected_no_retry_attempted, "HTTP %1\$s：服務未接受請求；未重試。", outcome.httpCode)
            }
            else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_no_usable_text_returned_the_response_was, "服務未回傳有效文字（空白或被過濾），測試未通過。")
        }
        return UiStrings.get(StringsRes.string.data_cloudtranscribe_ntext_only_connection_test_audio_support_was, "%1\$s\n僅文字連線測試，不代表支援音訊；沒有上傳錄音。", result)
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
