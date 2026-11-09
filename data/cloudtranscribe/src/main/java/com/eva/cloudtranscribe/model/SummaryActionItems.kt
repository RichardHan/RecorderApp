package com.eva.cloudtranscribe.model

import com.eva.cloudtranscribe.live.LiveCloudSettings

/**
 * Turns an existing summary into candidate to-do items. Pure, on-device and never calls a model: it only picks the
 * bullets the summary already contains, drops the "no points" placeholder, and removes duplicates so re-running it
 * cannot pile up the same line twice.
 */
object SummaryActionItems {

	/** Prefix every summary bullet starts with (see `Prompts.SUMMARY_CONCISE`). */
	private const val BULLET = "- "

	/** Markers that are not real to-dos. */
	private val NOT_ITEMS = setOf(
		LiveCloudSettings.NO_KEY_POINTS,
		"（尚無）",
		"目前無重點。",
	)

	/**
	 * Candidate items of [summary], in the order the summary lists them, without duplicates.
	 * Only lines that start with "- " are considered; anything else (headings, prose) is ignored.
	 */
	fun candidates(summary: String): List<String> {
		val seen = HashSet<String>()
		return summary.lines()
			.map { it.trim() }
			.filter { it.startsWith(BULLET) }
			.map { it.removePrefix(BULLET).trim() }
			.filter { it.isNotEmpty() && it !in NOT_ITEMS }
			.filter { seen.add(normalize(it)) }
			.toList()
	}

	/** Text used to decide "the same item was already added"; case and inner whitespace are ignored. */
	fun normalize(text: String): String = com.eva.utils.normalizeForComparison(text)

	/** True when [candidate] is already present in [existing] (compared with [normalize]). */
	fun isDuplicate(candidate: String, existing: Collection<String>): Boolean {
		val normalized = normalize(candidate)
		return normalized.isNotEmpty() && existing.any { normalize(it) == normalized }
	}

	/** Clipboard text for the unfinished items; fixed `- [ ] text` lines, nothing invented. */
	fun unfinishedAsClipboardText(items: List<Pair<String, Boolean>>): String =
		items.filterNot { (_, done) -> done }.joinToString("\n") { (text, _) -> "- [ ] ${text.trim()}" }
}
