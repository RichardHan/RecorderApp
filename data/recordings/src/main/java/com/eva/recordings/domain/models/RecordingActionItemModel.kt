package com.eva.recordings.domain.models

/** One locally stored to-do of a recording. No owner or deadline on purpose: the text keeps what was said. */
data class RecordingActionItemModel(
	val id: Long,
	val recordingId: Long,
	val text: String,
	val isDone: Boolean,
	val createdAtMillis: Long,
	/** "summary" when picked from an existing summary, "manual" when typed by the user. */
	val source: String,
) {
	val isFromSummary: Boolean get() = source == SOURCE_SUMMARY

	companion object {
		const val SOURCE_SUMMARY = "summary"
		const val SOURCE_MANUAL = "manual"
	}
}
