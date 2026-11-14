package com.eva.cloudtranscribe.title

import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord

/**
 * Title suggestions for the rename dialog (spec: market-title-suggest). Suggestions only ever fill the text field;
 * renaming still goes through the existing rename flow. The AI variant sends the latest summary text only.
 */
object TitleSuggestion {

	const val LOCAL_MAX = 30
	const val AI_MIN = 2
	const val AI_MAX = 40
	const val MAX_SUMMARY_CHARS = 4_000
	private const val NO_POINTS = "目前無重點"

	private val ILLEGAL = Regex("""[\\/:*?"<>|\r\n\t]""")
	private val BULLET = Regex("""^\s*(?:[-*•·]|\d+[.)、])\s*""")
	private val HEADING = Regex("""^\s*#+\s*""")
	private val TRAILING = Regex("""[。．.，,；;：:！!？?、\s]+$""")
	private val PREFIX = Regex("""^\s*(?:建議)?(?:標題|title)\s*[:：]\s*""", RegexOption.IGNORE_CASE)
	private val WRAP = Regex("""^[「『《〈"'“‘【\[(（]+|[」』》〉"'”’】\])）]+$""")

	/** Newest successful, non-empty summary that actually has points. */
	fun latestUsable(summaries: List<SummaryRecord>): SummaryRecord? =
		summaries.sortedByDescending { it.createdAtMillis }
			.firstOrNull { it.status == RunStatus.SUCCESS && it.text.isNotBlank() && it.text.trim() != NO_POINTS }

	/** Removes characters that cannot appear in a file name and collapses spaces. */
	fun sanitize(title: String): String = ILLEGAL.replace(title, " ").replace(Regex("""\s+"""), " ").trim()

	/** First meaningful summary line, cleaned and cut to [LOCAL_MAX]; null when nothing usable. */
	fun fromSummary(text: String): String? {
		val line = text.lines().asSequence()
			.filterNot { it.trimStart().startsWith("#") } // section headings are not titles
			.map { HEADING.replace(BULLET.replace(it, ""), "").replace("**", "").trim() }
			.firstOrNull { it.isNotEmpty() && it != NO_POINTS && !it.endsWith("：") && !it.endsWith(":") }
			?: return null
		val clean = sanitize(TRAILING.replace(line, ""))
		if (clean.length < AI_MIN) return null
		return if (clean.length <= LOCAL_MAX) clean else TRAILING.replace(clean.take(LOCAL_MAX), "")
	}

	fun prompt(summary: String): String =
		"請根據以下錄音摘要，用摘要使用的語言寫一個 20 字以內的錄音標題。只輸出標題本身一行，" +
			"不要引號、書名號、編號、日期或任何說明；不要加入摘要裡沒有的內容。\n\n摘要：\n" +
			summary.trim().take(MAX_SUMMARY_CHARS)

	/** Validated AI title or null (then nothing is filled in). */
	fun parseAi(reply: String): String? {
		val first = reply.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
		var t = PREFIX.replace(first, "")
		repeat(2) { t = WRAP.replace(TRAILING.replace(t.trim(), ""), "") }
		t = sanitize(TRAILING.replace(t, ""))
		return t.takeIf { it.length in AI_MIN..AI_MAX && it != NO_POINTS }
	}
}
