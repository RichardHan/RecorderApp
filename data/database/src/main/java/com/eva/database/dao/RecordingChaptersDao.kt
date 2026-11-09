package com.eva.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.eva.database.entity.RecordingChapterEntity
import kotlinx.coroutines.flow.Flow
import org.jetbrains.annotations.VisibleForTesting

@Dao
interface RecordingChaptersDao {

	@Upsert
	suspend fun insert(chapters: Collection<RecordingChapterEntity>)

	@Upsert
	suspend fun insertOrUpdate(chapter: RecordingChapterEntity): Long

	@Query("SELECT * FROM recording_chapters WHERE RECORDING_ID=:recordingId ORDER BY START_MS ASC")
	fun chaptersAsFlow(recordingId: Long): Flow<List<RecordingChapterEntity>>

	@Query("SELECT * FROM recording_chapters WHERE RECORDING_ID=:recordingId ORDER BY START_MS ASC")
	suspend fun chapters(recordingId: Long): List<RecordingChapterEntity>

	@Query("DELETE FROM recording_chapters WHERE RECORDING_ID=:recordingId")
	suspend fun deleteByRecordingId(recordingId: Long)

	@Query("DELETE FROM recording_chapters WHERE CHAPTER_ID=:chapterId")
	suspend fun deleteById(chapterId: Long)

	@VisibleForTesting
	@Query("DELETE FROM recording_chapters")
	suspend fun clearAllChapters()
}
