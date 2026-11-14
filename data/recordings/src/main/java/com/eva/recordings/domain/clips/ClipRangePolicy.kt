package com.eva.recordings.domain.clips

/** Why a clip range cannot be saved; null from [ClipRangePolicy.validate] means it can. */
enum class ClipRangeProblem {
	START_AFTER_END,
	OUT_OF_RECORDING,
	TOO_SHORT,
	TOO_LONG,
}

/**
 * Pure rules for a user-selected clip range. Times are milliseconds on the source recording time line and only ever
 * come from the user (typed, current position or a bookmark) — nothing here invents a time.
 */
object ClipRangePolicy {

	/** Same lower bound as the editor (`AudioClipConfig.MIN_CLIP_DURATION`). */
	const val MIN_CLIP_MS = 1_000L

	/** Suggested default maximum. */
	const val DEFAULT_MAX_CLIP_MS = 3 * 60_000L

	/** Hard upper bound so the whole meeting is never exported by mistake. */
	const val HARD_MAX_CLIP_MS = 10 * 60_000L

	/** Padding on each side of a bookmark. */
	const val BOOKMARK_PADDING_MS = 15_000L

	/** Default length when starting from the current play position. */
	const val DEFAULT_CLIP_MS = 30_000L

	fun validate(startMs: Long, endMs: Long, totalMs: Long, maxMs: Long = DEFAULT_MAX_CLIP_MS): ClipRangeProblem? {
		val limit = maxMs.coerceIn(MIN_CLIP_MS, HARD_MAX_CLIP_MS)
		return when {
			startMs < 0L || (totalMs > 0L && endMs > totalMs) -> ClipRangeProblem.OUT_OF_RECORDING
			endMs <= startMs -> ClipRangeProblem.START_AFTER_END
			endMs - startMs < MIN_CLIP_MS -> ClipRangeProblem.TOO_SHORT
			endMs - startMs > limit -> ClipRangeProblem.TOO_LONG
			else -> null
		}
	}

	/** Bookmark at [pointMs] -> [point - 15 s, point + 15 s], clamped to the recording. */
	fun aroundBookmark(pointMs: Long, totalMs: Long, paddingMs: Long = BOOKMARK_PADDING_MS): LongRange {
		val start = (pointMs - paddingMs).coerceAtLeast(0L)
		val end = if (totalMs > 0L) (pointMs + paddingMs).coerceAtMost(totalMs) else pointMs + paddingMs
		return start..end.coerceAtLeast(start)
	}

	/** From the current position forward [DEFAULT_CLIP_MS]; near the end it shifts back so the clip stays inside. */
	fun fromPosition(positionMs: Long, totalMs: Long, lengthMs: Long = DEFAULT_CLIP_MS): LongRange {
		val position = positionMs.coerceAtLeast(0L)
		if (totalMs <= 0L) return position..(position + lengthMs)
		val length = lengthMs.coerceAtMost(totalMs)
		val start = position.coerceIn(0L, (totalMs - length).coerceAtLeast(0L))
		return start..(start + length)
	}

	/** Default title is just the real range (no generated title). */
	fun defaultTitle(startMs: Long, endMs: Long): String = "片段 ${formatMs(startMs)}–${formatMs(endMs)}"

	fun formatMs(ms: Long): String {
		val total = ms.coerceAtLeast(0L) / 1000
		val h = total / 3600
		val m = (total % 3600) / 60
		val s = total % 60
		return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
	}

	/** Parses "m:ss", "h:mm:ss" or plain seconds into milliseconds; null when invalid. */
	fun parseTime(text: String): Long? {
		val parts = text.trim().split(":")
		if (parts.size > 3 || parts.any { it.isBlank() }) return null
		val numbers = parts.map { it.toLongOrNull() ?: return null }
		if (numbers.any { it < 0 }) return null
		if (numbers.size > 1 && numbers.drop(1).any { it >= 60 }) return null
		return numbers.fold(0L) { acc, n -> acc * 60 + n } * 1000
	}

	fun problemMessage(problem: ClipRangeProblem, maxMs: Long = DEFAULT_MAX_CLIP_MS): String = when (problem) {
		ClipRangeProblem.START_AFTER_END -> "結束時間必須晚於開始時間"
		ClipRangeProblem.OUT_OF_RECORDING -> "時間超出錄音範圍"
		ClipRangeProblem.TOO_SHORT -> "片段至少要 1 秒"
		ClipRangeProblem.TOO_LONG -> "片段最長 ${maxMs.coerceIn(MIN_CLIP_MS, HARD_MAX_CLIP_MS) / 60_000} 分鐘"
	}
}
