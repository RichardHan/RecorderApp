package com.eva.cloudtranscribe.title

import kotlinx.datetime.LocalDateTime

/**
 * File-name helpers for automatic AI titling after a post-recording summary.
 *
 * New names keep a sort-friendly date-time prefix from the recording's own timestamp:
 * `yyyy-MM-dd HHmm` (24-hour, zero-padded), then the sanitized title, then the original extension.
 * Example: `2026-11-20 1530 週會討論.m4a`.
 *
 * Original auto-generated names (the recorder's default `Prefix-20 Nov 15:30` date-time form, or
 * `Prefix-001` count form, with or without an extension) are detected so a manual rename is never overwritten.
 */
object AutoRecordingName {

	const val MAX_BASE_CHARS = 80

	private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
	private val DATE_TIME_NAME = Regex(
		"""^.+-\d{2} (?:${MONTHS.joinToString("|")}) \d{1,2}:\d{2}( \(\d+\))?(\.[A-Za-z0-9]+)?$""",
	)
	private val COUNT_NAME = Regex("""^.+-\d{3}( \(\d+\))?(\.[A-Za-z0-9]+)?$""")
	private val EXTENSION = Regex("""(\.[A-Za-z0-9]{1,8})$""")

	/** `2026-11-20 1530` from the recording's own [recordedAt]. */
	fun dateTimePrefix(recordedAt: LocalDateTime): String = buildString {
		append(recordedAt.year.toString().padStart(4, '0'))
		append('-')
		append(recordedAt.monthNumber.toString().padStart(2, '0'))
		append('-')
		append(recordedAt.dayOfMonth.toString().padStart(2, '0'))
		append(' ')
		append(recordedAt.hour.toString().padStart(2, '0'))
		append(recordedAt.minute.toString().padStart(2, '0'))
	}

	/** True when [displayName] still looks like the recorder's original auto-generated name. */
	fun isOriginalAutoName(displayName: String): Boolean {
		val name = displayName.trim()
		if (name.isEmpty()) return false
		return DATE_TIME_NAME.matches(name) || COUNT_NAME.matches(name)
	}

	fun extensionOf(displayName: String): String =
		EXTENSION.find(displayName.trim())?.groupValues?.get(1).orEmpty()

	fun titleFromDisplayName(displayName: String): String {
		val name = displayName.trim()
		val ext = extensionOf(name)
		return if (ext.isNotEmpty()) name.removeSuffix(ext) else name
	}

	/**
	 * Builds `yyyy-MM-dd HHmm <title><ext>`. [title] is sanitized and cut so the base (no extension)
	 * stays within [MAX_BASE_CHARS]. Null when the title is unusable.
	 */
	fun format(recordedAt: LocalDateTime, title: String, extension: String = ""): String? {
		val clean = TitleSuggestion.sanitize(title)
		if (clean.length < TitleSuggestion.AI_MIN) return null
		val prefix = dateTimePrefix(recordedAt)
		val ext = extension.takeIf { it.startsWith(".") || it.isEmpty() }.orEmpty()
		val budget = (MAX_BASE_CHARS - prefix.length - 1).coerceAtLeast(TitleSuggestion.AI_MIN)
		val body = if (clean.length <= budget) clean else clean.take(budget).trimEnd()
		if (body.length < TitleSuggestion.AI_MIN) return null
		return "$prefix $body$ext"
	}

	/** `name (n).ext` for collision retries; [attempt] is 2-based (first retry is 2). */
	fun withCollisionSuffix(displayName: String, attempt: Int, knownExtension: String? = null): String {
		if (attempt <= 1) return displayName
		val ext = (knownExtension ?: extensionOf(displayName)).takeIf { it.startsWith(".") }.orEmpty()
		val base = if (ext.isNotEmpty() && displayName.endsWith(ext)) displayName.removeSuffix(ext) else displayName
		return "$base ($attempt)$ext"
	}
}
