package com.eva.feature_recordings.recap

import com.eva.feature_recordings.R as StringsRes
import com.eva.utils.UiStrings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RecapPeriod(val label: String, val days: Int) {
	TODAY(UiStrings.get(StringsRes.string.feature_recordings_today, "今天"), 0),
	WEEK(UiStrings.get(StringsRes.string.feature_recordings_last_7_days, "最近 7 天"), 7),
	MONTH(UiStrings.get(StringsRes.string.feature_recordings_last_30_days, "最近 30 天"), 30),
}

data class RecapRecording(val id: Long, val title: String, val recordedAtMillis: Long, val durationMs: Long)

data class RecapEntry(
	val recording: RecapRecording,
	/** Null when there is no successful summary. Empty when the summary says there are no points. */
	val points: List<String>?,
	val openItems: List<String>,
	val moreOpenItems: Int,
	val failed: Boolean = false,
)

data class Recap(val entries: List<RecapEntry>, val moreRecordings: Int)

/** Local recap of recent recordings (spec: market-recent-recap). Only arranges data that already exists. */
object RecentRecap {

	const val MAX_RECORDINGS = 50
	const val MAX_POINTS = 3
	const val MAX_ITEMS = 5
	private const val NO_POINTS = "目前無重點"
	private val BULLET = Regex("""^\s*(?:[-*•·]|\d+[.)、])\s*""")

	fun periodStart(period: RecapPeriod, nowMillis: Long, zone: ZoneId): Long =
		if (period == RecapPeriod.TODAY) Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
		else nowMillis - period.days * 86_400_000L

	/** Recordings in [startMillis, nowMillis], newest first, capped at [MAX_RECORDINGS]; second = how many were cut. */
	fun select(all: List<RecapRecording>, startMillis: Long, nowMillis: Long): Pair<List<RecapRecording>, Int> {
		val inRange = all.filter { it.recordedAtMillis in startMillis..nowMillis }.sortedByDescending { it.recordedAtMillis }
		return inRange.take(MAX_RECORDINGS) to (inRange.size - MAX_RECORDINGS).coerceAtLeast(0)
	}

	/** First [MAX_POINTS] bullet lines without markers; headings and the "no points" reply are skipped. */
	fun points(summary: String): List<String> = summary.lines().asSequence()
		.map { it.trim() }
		.filter { it.isNotEmpty() && !it.startsWith("#") && it != NO_POINTS && !it.endsWith("：") && !it.endsWith(":") }
		.map { BULLET.replace(it, "").replace("**", "").trim() }
		.filter { it.isNotEmpty() }
		.take(MAX_POINTS)
		.toList()

	fun entry(recording: RecapRecording, summary: String?, openItems: List<String>): RecapEntry = RecapEntry(
		recording = recording,
		points = summary?.let(::points),
		openItems = openItems.take(MAX_ITEMS),
		moreOpenItems = (openItems.size - MAX_ITEMS).coerceAtLeast(0),
	)

	fun duration(ms: Long): String {
		val s = ms / 1000
		return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
	}

	fun date(millis: Long, zone: ZoneId): String =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(millis))

	fun toMarkdown(recap: Recap, period: RecapPeriod, zone: ZoneId): String = buildString {
		append(UiStrings.get(StringsRes.string.feature_recordings_recent_review_alt, "# 近期回顧（")).append(period.label).append("）\n")
		if (recap.entries.isEmpty()) { append(UiStrings.get(StringsRes.string.feature_recordings_nno_recordings_during_this_period_n, "\n這段期間沒有錄音\n")); return@buildString }
		for (e in recap.entries) {
			append("\n## ").append(e.recording.title).append('\n')
			append(date(e.recording.recordedAtMillis, zone)).append("・").append(duration(e.recording.durationMs)).append('\n')
			when {
				e.failed -> append(UiStrings.get(StringsRes.string.feature_recordings_n_could_not_load_n, "\n（讀取失敗）\n"))
				e.points == null -> append(UiStrings.get(StringsRes.string.feature_recordings_n_no_summary_yet_n, "\n（尚無摘要）\n"))
				e.points.isEmpty() -> append(UiStrings.get(StringsRes.string.feature_recordings_n_no_highlights_n, "\n（沒有重點）\n"))
				else -> { append('\n'); e.points.forEach { append("- ").append(it).append('\n') } }
			}
			if (e.openItems.isNotEmpty()) {
				append(UiStrings.get(StringsRes.string.feature_recordings_nopen_tasks_n, "\n未完成待辦：\n"))
				e.openItems.forEach { append("- [ ] ").append(it).append('\n') }
				if (e.moreOpenItems > 0) append(UiStrings.get(StringsRes.string.feature_recordings_plus, "- 另有 ")).append(e.moreOpenItems).append(UiStrings.get(StringsRes.string.feature_recordings_more_items_n, " 條\n"))
			}
		}
		if (recap.moreRecordings > 0) append(UiStrings.get(StringsRes.string.feature_recordings_nplus, "\n另有 ")).append(recap.moreRecordings).append(UiStrings.get(StringsRes.string.feature_recordings_other_recordings_omitted_n, " 段錄音未列出\n"))
	}
}
