package com.eva.cloudtranscribe.live

import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.HttpTransport
import com.eva.cloudtranscribe.api.OpenAiCompatibleClient
import com.eva.cloudtranscribe.api.Redactor
import com.eva.cloudtranscribe.model.ReasoningEffort
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.SSLException

/**
 * One explicit test of the SAVED independent key-point summary settings: same prompt builder and client as the live
 * session, a tiny synthetic Traditional Chinese snippet, empty previous key points, bounded tokens/time, no retry.
 * Never returns raw server bodies or secrets.
 */
object SummaryProbe {
	const val TOKEN_LIMIT = 200
	const val TIMEOUT_SECONDS = 20
	const val SAMPLE_TEXT = "Speaker 1: 下週三前完成報價單。\nSpeaker 2: 好，我負責，週二先給初稿。"

	/** Which key the test (and live mode) would use; reuse only when explicitly selected. */
	fun selectKey(settings: LiveCloudSettings, summaryKey: String?, transcribeKey: String?): String? =
		if (settings.summaryReuseTranscribeKey) transcribeKey else summaryKey

	fun buildPrompt(settings: LiveCloudSettings): String =
		TranscriptMerge.summaryPrompt(settings.summaryPrompt, "", SAMPLE_TEXT, "")

	fun run(settings: LiveCloudSettings, summaryKey: String?, transcribeKey: String?, transport: HttpTransport): String {
		val key = selectKey(settings, summaryKey, transcribeKey)
		if (key.isNullOrBlank()) return if (settings.summaryReuseTranscribeKey) "已選擇沿用轉錄 Key，但轉錄 Key 尚未設定。" else "尚未設定摘要 API Key，請先儲存。"
		val uri = runCatching { URI(settings.summaryBaseUrl.trim()) }.getOrNull()
		if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null)
			return "請先儲存有效的 HTTPS 摘要 Endpoint（不可含帳密、查詢參數或片段）。"
		if (settings.summaryModel.isBlank()) return "請先儲存摘要模型名稱。"
		var transportError: String? = null
		var calls = 0
		val guarded = HttpTransport { url, headers, body, timeout ->
			check(++calls == 1) { "Probe must not retry" }
			try { transport.postJson(url, headers, body, timeout) } catch (e: IOException) {
				transportError = when (e) {
					is SocketTimeoutException -> "連線逾時（$TIMEOUT_SECONDS 秒）；未重試。"
					is SSLException -> "TLS／憑證驗證失敗；未重試。"
					else -> "網路連線失敗；請檢查網路與摘要 Endpoint。未重試。"
				}
				throw IOException("Summary probe network failure")
			}
		}
		val start = System.nanoTime()
		val outcome = try {
			OpenAiCompatibleClient(settings.summaryBaseUrl, key, settings.summaryModel, ReasoningEffort.OFF, guarded, TOKEN_LIMIT, TIMEOUT_SECONDS)
				.complete(buildPrompt(settings))
		} catch (e: Exception) { return "測試失敗：設定或回應格式錯誤；未重試。" }
		transportError?.let { return it }
		val ms = (System.nanoTime() - start) / 1_000_000
		val keyLabel = if (settings.summaryReuseTranscribeKey) "沿用轉錄 Key" else "摘要 Key"
		val result = when (outcome) {
			is CallOutcome.Text -> if (outcome.text.isNotBlank() && !outcome.text.trimStart().startsWith("<")) {
				val preview = Redactor.redact(outcome.text, key).replace(Regex("\\s+"), " ").take(60)
				"摘要測試成功 · ${ms} ms · ${uri.host} · ${Redactor.redact(settings.summaryModel, key)} · $keyLabel\n回覆預覽：$preview"
			} else "服務回傳空白或非文字回應，測試未通過。"
			is CallOutcome.Failure -> when (outcome.httpCode) {
				401, 403 -> "HTTP ${outcome.httpCode}：摘要 API Key 或存取權限錯誤。"
				404 -> "HTTP 404：摘要 Endpoint 路徑或模型不存在。"
				429 -> "HTTP 429：配額不足或請求頻率限制；未重試。"
				400, 422 -> "HTTP ${outcome.httpCode}：請求參數或模型不受服務接受。"
				null -> "回應格式／設定錯誤，測試未通過。"
				else -> "HTTP ${outcome.httpCode}：服務未接受請求；未重試。"
			}
			else -> "服務未回傳有效文字（空白或被過濾），測試未通過。"
		}
		return "$result\n只送出一段內建的短範例文字，沒有上傳錄音或逐字稿。"
	}
}
