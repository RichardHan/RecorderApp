package com.eva.recordings.domain.models

/** A short clip exported from recording [sourceAudioId]; times are on the source time line. */
data class RecordingClipModel(
	val id: Long,
	val sourceAudioId: Long,
	val title: String,
	val startMs: Long,
	val endMs: Long,
	val filePath: String,
	val mimeType: String,
	val createdAtMillis: Long,
) {
	val durationMs: Long
		get() = (endMs - startMs).coerceAtLeast(0L)
}
