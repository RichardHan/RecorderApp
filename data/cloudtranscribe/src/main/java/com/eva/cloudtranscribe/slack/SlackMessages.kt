package com.eva.cloudtranscribe.slack

import com.eva.cloudtranscribe.live.LiveCloudSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a queued Slack message is for. */
enum class SlackMessageKind { TEST, START, KEY_POINTS, END }

/**
 * Pure text building for Slack. Only text the app already produced locally is used (key points, nothing else);
 * no audio, transcript excerpts, API keys or the webhook URL ever end up here.
 */
object SlackMessages {

	/** Slack recommends keeping `text` under 4,000 characters. */
	const val MAX_TEXT = 4_000
	const val TRUNCATED_MARK = "\n…（已截斷）"

	const val TEST_TEXT = "RecorderApp 測試：Slack 連線正常。"

	private val PLACEHOLDERS = setOf(
		LiveCloudSettings.NO_KEY_POINTS,
		"${LiveCloudSettings.NO_KEY_POINTS}。",
		"（尚無）",
	)

	/** Key-point bullets of a live summary; "目前無重點" and blanks yield an empty list. */
	fun bullets(keyPoints: String?): List<String> {
		if (keyPoints.isNullOrBlank()) return emptyList()
		val lines = keyPoints.lines().map { it.trim() }.filter { it.isNotEmpty() }
		val seen = HashSet<String>()
		return lines
			.map { line -> line.removePrefix("- ").removePrefix("• ").removePrefix("* ").trim() }
			.filter { it.isNotEmpty() && it !in PLACEHOLDERS }
			.filter { seen.add(normalize(it)) }
	}

	/** Items of [current] that were not already sent (new or reworded). */
	fun diff(alreadySent: List<String>, current: List<String>): List<String> {
		val sent = alreadySent.map(::normalize).toHashSet()
		return current.filter { normalize(it) !in sent }
	}

	fun normalize(text: String): String = text.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('。', '.')

	fun startText(startedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
		val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(startedAtMillis))
		val offset = zone.rules.getOffset(Instant.ofEpochMilli(startedAtMillis)).id.let { if (it == "Z") "+00:00" else it }
		return "🎙️ RecorderApp：開始錄音（$time，${zone.id} UTC$offset）。狀態：進行中。"
	}

	const val MID_MAX_ITEMS = 3

	/** Mid-recording update: merge/dedupe is already done by the caller; cap at [maxItems], no footer. */
	fun keyPointsText(newItems: List<String>, maxItems: Int = MID_MAX_ITEMS): String? {
		val items = capItems(newItems, maxItems)
		if (items.isEmpty()) return null
		return truncate(
			buildString {
				appendLine("📝 新重點：")
				items.forEach { appendLine("• $it") }
			}.trimEnd(),
		)
	}

	/** Dedupe (same rules as [bullets]) then keep at most [max] items, preserving order. */
	fun capItems(items: List<String>, max: Int): List<String> {
		if (max <= 0) return emptyList()
		val seen = HashSet<String>()
		return items
			.map { it.trim() }
			.filter { it.isNotEmpty() && it !in PLACEHOLDERS }
			.filter { seen.add(normalize(it)) }
			.take(max)
	}

	/** @deprecated Use [SlackEndMessage.compose]; kept for older call sites and tests. */
	fun endText(finalItems: List<String>): String? {
		if (finalItems.isEmpty()) return null
		return SlackEndMessage.compose(title = null, durationMs = null, summaryText = null, fallbackItems = finalItems)
	}

	fun truncate(text: String, max: Int = MAX_TEXT): String =
		if (text.length <= max) text else text.take(max - TRUNCATED_MARK.length) + TRUNCATED_MARK

	private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
	private val LONG_NUMBER = Regex("(?<![0-9])[0-9][0-9 -]{6,}[0-9](?![0-9])")

	/** Optional local masking of e-mail addresses and long digit runs (phone / account numbers). */
	fun mask(text: String): String = text.replace(EMAIL, "[已隱藏]").replace(LONG_NUMBER, "[已隱藏]")

	fun payload(text: String): String = JsonObject(mapOf("text" to JsonPrimitive(text))).toString()

	/** Stable identity of a message, for de-duplication. */
	fun hash(kind: SlackMessageKind, sessionId: String, text: String): String =
		"%08x".format("$kind|$sessionId|$text".hashCode())

	/** Only real Slack incoming webhooks are accepted (https, hooks.slack.com). */
	fun isValidWebhookUrl(url: String): Boolean {
		val trimmed = url.trim()
		return trimmed.startsWith("https://hooks.slack.com/services/") && trimmed.length > "https://hooks.slack.com/services/".length + 10 &&
			trimmed.none { it.isWhitespace() }
	}
}

/** Result of one webhook POST, classified per Slack's documented errors. */
sealed interface SlackSendResult {
	data object Sent : SlackSendResult
	data object Suppressed : SlackSendResult
	data class RetryAfter(val seconds: Long) : SlackSendResult
	data class Retry(val reason: String) : SlackSendResult
	/** Do not retry as-is (bad payload, revoked webhook, archived channel, ...); the user must fix the setup. */
	data class Permanent(val reason: String) : SlackSendResult
}

object SlackSendPolicy {

	private val PERMANENT_ERRORS = setOf(
		"invalid_payload", "no_service", "no_service_id", "no_team", "team_disabled", "action_prohibited",
		"channel_not_found", "channel_is_archived", "invalid_token", "no_text", "user_not_found",
		"posting_to_general_channel_denied", "too_many_attachments", "missing_text_or_fallback_or_attachments",
		"invalid_blocks", "invalid_blocks_format",
	)

	const val DEFAULT_RETRY_AFTER_SECONDS = 30L

	fun classify(code: Int, body: String, retryAfterHeader: String?): SlackSendResult {
		val error = body.trim().take(80)
		return when {
			code in 200..299 -> SlackSendResult.Sent
			code == 429 -> SlackSendResult.RetryAfter(
				retryAfterHeader?.trim()?.toLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_RETRY_AFTER_SECONDS,
			)
			error in PERMANENT_ERRORS -> SlackSendResult.Permanent(error)
			code in setOf(400, 403, 404, 410) -> SlackSendResult.Permanent(error.ifEmpty { "HTTP $code" })
			else -> SlackSendResult.Retry(if (error.isEmpty()) "HTTP $code" else "HTTP $code $error")
		}
	}

	/** User-facing text for a result; never includes the webhook URL. */
	fun describe(result: SlackSendResult): String = when (result) {
		SlackSendResult.Sent -> "已送出"
		SlackSendResult.Suppressed -> "已停止傳送"
		is SlackSendResult.RetryAfter -> "Slack 要求稍候（${result.seconds} 秒後重試）"
		is SlackSendResult.Retry -> "暫時失敗，稍後重試（${result.reason}）"
		is SlackSendResult.Permanent -> "Slack 拒絕（${result.reason}），請檢查 Webhook 設定"
	}
}

/** Mid-recording key-point batching: 關閉, or one message every 5 / 10 / 15 minutes. */
object SlackThrottle {
	const val OFF_INTERVAL_MINUTES = 0
	const val MIN_INTERVAL_MINUTES = 0
	const val MAX_INTERVAL_MINUTES = 15
	const val DEFAULT_INTERVAL_MINUTES = 5
	val OPTIONS_MINUTES = listOf(0, 5, 10, 15)

	/** Maps a stored value (including older 2–10 settings) onto 關閉 / 5 / 10 / 15. */
	fun normalize(minutes: Int): Int = when {
		minutes <= 0 -> OFF_INTERVAL_MINUTES
		minutes <= 7 -> 5
		minutes <= 12 -> 10
		else -> 15
	}

	fun isOff(minutes: Int): Boolean = normalize(minutes) == OFF_INTERVAL_MINUTES

	fun intervalMillis(minutes: Int): Long {
		val n = normalize(minutes)
		return if (n == 0) 0L else n * 60_000L
	}

	/**
	 * Milliseconds to wait before the next key-point update may go out (0 = now).
	 * When [intervalMillis] is 0 (關閉), the update is never due.
	 * The first mid-recording message waits a full interval from [sessionStartedAtMillis].
	 */
	fun delayUntilDue(
		lastSentAtMillis: Long?,
		nowMillis: Long,
		intervalMillis: Long,
		sessionStartedAtMillis: Long? = null,
	): Long {
		if (intervalMillis <= 0L) return Long.MAX_VALUE
		val anchor = lastSentAtMillis ?: sessionStartedAtMillis ?: return 0L
		return (anchor + intervalMillis - nowMillis).coerceAtLeast(0L)
	}
}
