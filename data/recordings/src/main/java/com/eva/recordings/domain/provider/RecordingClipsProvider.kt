package com.eva.recordings.domain.provider

import com.eva.recordings.domain.models.RecordingClipModel
import java.io.File
import kotlinx.coroutines.flow.Flow

/** Local clips of recordings: rows in Room plus files in app-private storage. The source audio is never touched. */
interface RecordingClipsProvider {

	fun clipsAsFlow(audioId: Long): Flow<List<RecordingClipModel>>

	/** App-private directory for clips of [audioId] (created on demand). */
	fun clipsDirectory(audioId: Long): File

	/**
	 * Moves [exportedFile] into [clipsDirectory] and stores the row. On any failure the file is deleted and no row is
	 * written, so there is never a list row without a file or a stray file without a row.
	 */
	suspend fun saveExportedClip(
		audioId: Long,
		title: String,
		startMs: Long,
		endMs: Long,
		mimeType: String,
		exportedFile: File,
	): Result<RecordingClipModel>

	suspend fun rename(clip: RecordingClipModel, title: String)

	/** Deletes the clip file and row only. */
	suspend fun delete(clip: RecordingClipModel)

	/** Removes every clip (files and rows) of the given recordings, e.g. when they are trashed. */
	suspend fun deleteAllFor(audioIds: List<Long>)
}
