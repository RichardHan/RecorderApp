package com.eva.recordings.domain.provider

import com.eva.recordings.domain.models.RecordingChapterModel
import kotlinx.coroutines.flow.Flow

/**
 * Local chapter list of one recording. Chapters are only written after a whole successful generation, so a cancelled
 * or failed run can never leave a partial目录 behind.
 */
interface RecordingChaptersProvider {

	fun chaptersAsFlow(recordingId: Long): Flow<List<RecordingChapterModel>>

	suspend fun chapters(recordingId: Long): List<RecordingChapterModel>

	/** Replaces the whole list of [recordingId] with [chapters] in one transaction-like call. */
	suspend fun replaceAll(recordingId: Long, chapters: List<RecordingChapterModel>)

	suspend fun rename(chapter: RecordingChapterModel, title: String)

	suspend fun delete(chapter: RecordingChapterModel)
}
