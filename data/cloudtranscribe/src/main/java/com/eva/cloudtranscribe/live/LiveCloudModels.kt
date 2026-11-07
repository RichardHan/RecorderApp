package com.eva.cloudtranscribe.live

import kotlinx.serialization.Serializable

/**
 * Advanced "live cloud" mode settings (non-secret). OFF by default: with defaults no request is ever made.
 * Cloud transcription reuses the existing cloud ASR settings/key; key-point summary uses its own endpoint/key/model.
 */
@Serializable
data class LiveCloudSettings(
	val enabled: Boolean = false,
	/** Set when the user explicitly accepted the audio/text upload + cost notice; enabling requires it. */
	val consentAcceptedAtMillis: Long? = null,
	val transcribeIntervalSec: Int = DEFAULT_TRANSCRIBE_INTERVAL,
	val summaryIntervalSec: Int = DEFAULT_SUMMARY_INTERVAL,
	val firstSummaryDelaySec: Int = DEFAULT_FIRST_SUMMARY_DELAY,
	/** First summary also waits for at least one successful cloud transcript chunk. */
	val requireCloudBeforeFirstSummary: Boolean = true,
	/** Feed newest offline (on-device) finalized text, marked provisional, for audio not yet covered by cloud. */
	val offlineSupplement: Boolean = true,
	val summaryBaseUrl: String = "",
	val summaryModel: String = DEFAULT_SUMMARY_MODEL,
	val summaryPrompt: String = DEFAULT_SUMMARY_PROMPT,
	/** Explicit choice to use the cloud-transcription API key for summary too (never implicit). */
	val summaryReuseTranscribeKey: Boolean = false,
) {
	val transcribeIntervalMs: Long get() = transcribeIntervalSec.coerceIn(MIN_TRANSCRIBE_INTERVAL, MAX_TRANSCRIBE_INTERVAL) * 1000L
	val summaryIntervalMs: Long get() = summaryIntervalSec.coerceIn(MIN_SUMMARY_INTERVAL, MAX_SUMMARY_INTERVAL) * 1000L
	val firstSummaryDelayMs: Long get() = firstSummaryDelaySec.coerceIn(0, MAX_FIRST_DELAY) * 1000L
	val isActive: Boolean get() = enabled && consentAcceptedAtMillis != null

	fun normalized() = copy(
		transcribeIntervalSec = transcribeIntervalSec.coerceIn(MIN_TRANSCRIBE_INTERVAL, MAX_TRANSCRIBE_INTERVAL),
		summaryIntervalSec = summaryIntervalSec.coerceIn(MIN_SUMMARY_INTERVAL, MAX_SUMMARY_INTERVAL),
		firstSummaryDelaySec = firstSummaryDelaySec.coerceIn(0, MAX_FIRST_DELAY),
		summaryPrompt = summaryPrompt.ifBlank { DEFAULT_SUMMARY_PROMPT },
		summaryModel = summaryModel.trim(),
		summaryBaseUrl = summaryBaseUrl.trim(),
	)

	fun validateSummary(hasSummaryKey: Boolean, hasTranscribeKey: Boolean): String? = when {
		summaryBaseUrl.isBlank() -> "尚未設定摘要 Endpoint"
		!summaryBaseUrl.trim().startsWith("https://", ignoreCase = true) -> "摘要 Endpoint 必須是 https://"
		summaryModel.isBlank() -> "尚未設定摘要模型"
		summaryReuseTranscribeKey && !hasTranscribeKey -> "選擇沿用轉錄 Key，但轉錄 Key 尚未設定"
		!summaryReuseTranscribeKey && !hasSummaryKey -> "尚未設定摘要 API Key"
		else -> null
	}

	companion object {
		const val DEFAULT_TRANSCRIBE_INTERVAL = 60
		const val DEFAULT_SUMMARY_INTERVAL = 30
		const val DEFAULT_FIRST_SUMMARY_DELAY = 90
		const val MIN_TRANSCRIBE_INTERVAL = 30
		const val MAX_TRANSCRIBE_INTERVAL = 600
		const val MIN_SUMMARY_INTERVAL = 15
		const val MAX_SUMMARY_INTERVAL = 600
		const val MAX_FIRST_DELAY = 1800
		const val DEFAULT_SUMMARY_MODEL = "deepseek-v4.1-flash"
		const val DEFAULT_SUMMARY_PROMPT =
			"你是會議即時重點整理助手。根據「目前重點」與「新增逐字稿」，輸出更新後的完整重點清單（繁體中文、條列、精簡）。" +
				"只整理重點、決議與待辦，不要改寫或重抄逐字稿，不要捏造內容。標示為「暫定」的文字來自離線辨識，可能有錯字，僅供參考。"
	}
}

/** One finished cloud transcript chunk covering [startMs, endMs) of recorded (non-paused) audio. */
data class CloudSpan(val startMs: Long, val endMs: Long, val text: String, val ok: Boolean = true, val error: String? = null)

/** One offline (on-device) finalized line; [startMs] is on the same recorded-audio timeline. */
data class OfflineLine(val startMs: Long, val text: String)

enum class LiveCloudPhase { OFF, WAITING_FIRST_CHUNK, TRANSCRIBING, IDLE, SUMMARIZING, FINISHING, DONE }

/** UI snapshot of the live cloud session. */
data class LiveCloudState(
	val active: Boolean = false,
	val phase: LiveCloudPhase = LiveCloudPhase.OFF,
	val cloudSpans: List<CloudSpan> = emptyList(),
	val provisional: List<OfflineLine> = emptyList(),
	val keyPoints: String = "",
	val keyPointsAtMs: Long? = null,
	val transcribeError: String? = null,
	val summaryError: String? = null,
	val pendingChunks: Int = 0,
	val skippedMs: Long = 0,
	val summaryModel: String? = null,
	val transcribeModel: String? = null,
)
