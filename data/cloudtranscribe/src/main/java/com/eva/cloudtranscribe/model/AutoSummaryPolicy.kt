package com.eva.cloudtranscribe.model

/** Spec: docs/proposals/market-auto-summary.md. Pure so the cost-relevant decision is unit tested. */
object AutoSummaryPolicy {
	/**
	 * True only when the opt-in is on, this cloud run fully succeeded with non-blank text and there is no
	 * non-cancelled summary for [transcriptId] yet (so resumes/re-runs never pay twice).
	 */
	fun shouldStart(
		settings: CloudSettings,
		status: RunStatus,
		text: String,
		transcriptId: String,
		existing: List<SummaryRecord>,
	): Boolean = settings.autoSummaryAfterTranscript &&
		status == RunStatus.SUCCESS &&
		text.isNotBlank() &&
		existing.none { it.sourceTranscriptId == transcriptId && it.status != RunStatus.CANCELLED }
}
