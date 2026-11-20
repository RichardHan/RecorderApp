package com.eva.cloudtranscribe.live

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings

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
		TranscriptMerge.summaryPrompt(settings.summaryPrompt, "", SAMPLE_TEXT, "", meetingNotes = settings.meetingNotes)

	fun run(settings: LiveCloudSettings, summaryKey: String?, transcribeKey: String?, transport: HttpTransport): String {
		val key = selectKey(settings, summaryKey, transcribeKey)
		if (key.isNullOrBlank()) return if (settings.summaryReuseTranscribeKey) UiStrings.get(StringsRes.string.data_cloudtranscribe_save_a_transcription_key_before_reusing_it_alt, "已選擇沿用轉錄 Key，但轉錄 Key 尚未設定。") else UiStrings.get(StringsRes.string.data_cloudtranscribe_save_a_summary_api_key_first, "尚未設定摘要 API Key，請先儲存。")
		val uri = runCatching { URI(settings.summaryBaseUrl.trim()) }.getOrNull()
		if (uri?.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null)
			return UiStrings.get(StringsRes.string.data_cloudtranscribe_save_a_valid_https_summary_endpoint_without, "請先儲存有效的 HTTPS 摘要 Endpoint（不可含帳密、查詢參數或片段）。")
		if (settings.summaryModel.isBlank()) return UiStrings.get(StringsRes.string.data_cloudtranscribe_save_a_summary_model_first, "請先儲存摘要模型名稱。")
		var transportError: String? = null
		var calls = 0
		val guarded = HttpTransport { url, headers, body, timeout ->
			check(++calls == 1) { "Probe must not retry" }
			try { transport.postJson(url, headers, body, timeout) } catch (e: IOException) {
				transportError = when (e) {
					is SocketTimeoutException -> UiStrings.get(StringsRes.string.data_cloudtranscribe_connection_timed_out_after_seconds_no_retry, "連線逾時（%1\$s 秒）；未重試。", TIMEOUT_SECONDS)
					is SSLException -> UiStrings.get(StringsRes.string.data_cloudtranscribe_tls_or_certificate_verification_failed_no_retry, "TLS／憑證驗證失敗；未重試。")
					else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_connection_failed_check_the_network_and_summary, "網路連線失敗；請檢查網路與摘要 Endpoint。未重試。")
				}
				throw IOException("Summary probe network failure")
			}
		}
		val start = System.nanoTime()
		val outcome = try {
			OpenAiCompatibleClient(settings.summaryBaseUrl, key, settings.summaryModel, ReasoningEffort.OFF, guarded, TOKEN_LIMIT, TIMEOUT_SECONDS)
				.complete(buildPrompt(settings))
		} catch (e: Exception) { return UiStrings.get(StringsRes.string.data_cloudtranscribe_test_failed_check_the_settings_and_response, "測試失敗：設定或回應格式錯誤；未重試。") }
		transportError?.let { return it }
		val ms = (System.nanoTime() - start) / 1_000_000
		val keyLabel = if (settings.summaryReuseTranscribeKey) UiStrings.get(StringsRes.string.data_cloudtranscribe_transcription_key, "沿用轉錄 Key") else UiStrings.get(StringsRes.string.data_cloudtranscribe_summary_key, "摘要 Key")
		val result = when (outcome) {
			is CallOutcome.Text -> if (outcome.text.isNotBlank() && !outcome.text.trimStart().startsWith("<")) {
				val preview = Redactor.redact(outcome.text, key).replace(Regex("\\s+"), " ").take(60)
				UiStrings.get(StringsRes.string.data_cloudtranscribe_summary_test_passed_ms_nresponse_preview, "摘要測試成功 · %1\$s ms · %2\$s · %3\$s · %4\$s\n回覆預覽：%5\$s", ms, uri.host, Redactor.redact(settings.summaryModel, key), keyLabel, preview)
			} else UiStrings.get(StringsRes.string.data_cloudtranscribe_the_service_returned_an_empty_or_non_alt, "服務回傳空白或非文字回應，測試未通過。")
			is CallOutcome.Failure -> when (outcome.httpCode) {
				401, 403 -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_check_your_summary_key_and_permissions, "HTTP %1\$s：摘要 API Key 或存取權限錯誤。", outcome.httpCode)
				404 -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_404_summary_endpoint_or_model_not, "HTTP 404：摘要 Endpoint 路徑或模型不存在。")
				429 -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_429_quota_or_rate_limit_reached, "HTTP 429：配額不足或請求頻率限制；未重試。")
				400, 422 -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_the_service_rejected_the_parameters_or, "HTTP %1\$s：請求參數或模型不受服務接受。", outcome.httpCode)
				null -> UiStrings.get(StringsRes.string.data_cloudtranscribe_test_failed_invalid_response_or_configuration, "回應格式／設定錯誤，測試未通過。")
				else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_http_request_rejected_no_retry_attempted, "HTTP %1\$s：服務未接受請求；未重試。", outcome.httpCode)
			}
			else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_no_usable_text_returned_the_response_was, "服務未回傳有效文字（空白或被過濾），測試未通過。")
		}
		return UiStrings.get(StringsRes.string.data_cloudtranscribe_nonly_built_in_sample_text_was_sent, "%1\$s\n只送出一段內建的短範例文字，沒有上傳錄音或逐字稿。", result)
	}
}
