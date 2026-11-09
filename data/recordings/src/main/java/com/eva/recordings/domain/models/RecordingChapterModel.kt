package com.eva.recordings.domain.models

/** One chapter of a recording; [startMs] comes from a real transcript segment, never from the model. */
data class RecordingChapterModel(
	val id: Long,
	val recordingId: Long,
	val title: String,
	val startMs: Long,
	val sourceSentenceIndex: Int,
	val createdAtMillis: Long,
	/** Transcript the chapter was derived from; a newer revision means "may be stale". */
	val transcriptRevision: String,
)
