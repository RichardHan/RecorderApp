package com.eva.cloudtranscribe.title

import com.eva.cloudtranscribe.model.SummaryRecord

/** Pure rules for whether and how to auto-rename after a post-recording summary. */
object AutoTitlePolicy {

	const val SOURCE_AI = "ai"
	const val SOURCE_SUMMARY = "summary"

	fun shouldAttempt(
		settingOn: Boolean,
		alreadyApplied: Boolean,
		displayName: String,
		usable: SummaryRecord?,
	): Boolean = settingOn && !alreadyApplied && AutoRecordingName.isOriginalAutoName(displayName) && usable != null

	/**
	 * Prefers a validated AI title; falls back to the first meaningful summary line.
	 * [aiReply] is null when the AI call was not made or failed.
	 */
	fun chooseTitle(aiReply: String?, summaryText: String): Pair<String, String>? {
		TitleSuggestion.parseAi(aiReply.orEmpty())?.let { return it to SOURCE_AI }
		val local = TitleSuggestion.fromSummary(summaryText) ?: return null
		return local to SOURCE_SUMMARY
	}
}
