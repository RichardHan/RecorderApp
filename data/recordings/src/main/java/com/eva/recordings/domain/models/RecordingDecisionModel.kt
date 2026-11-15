package com.eva.recordings.domain.models

/** One decision of a recording; [startMs] only ever comes from a real transcript sentence. */
data class RecordingDecisionModel(
	val id: Long,
	val recordingId: Long,
	val text: String,
	val startMs: Long? = null,
	val sourceSentenceIndex: Int? = null,
	val source: String = SOURCE_MANUAL,
	val createdAtMillis: Long,
	val transcriptRevision: String? = null,
) {
	val isExtracted: Boolean get() = source == SOURCE_EXTRACTED

	companion object {
		const val SOURCE_EXTRACTED = "extracted"
		const val SOURCE_MANUAL = "manual"
	}
}
