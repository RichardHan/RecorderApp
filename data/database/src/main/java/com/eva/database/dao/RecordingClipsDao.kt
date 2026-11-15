package com.eva.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.eva.database.entity.RecordingClipEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingClipsDao {

	@Upsert
	suspend fun insertOrUpdate(clip: RecordingClipEntity): Long

	@Query("SELECT * FROM recording_clips WHERE SOURCE_AUDIO_ID=:audioId ORDER BY START_MS ASC, CLIP_ID ASC")
	fun clipsAsFlow(audioId: Long): Flow<List<RecordingClipEntity>>

	@Query("SELECT * FROM recording_clips WHERE SOURCE_AUDIO_ID IN (:audioIds)")
	suspend fun clipsOf(audioIds: List<Long>): List<RecordingClipEntity>

	@Query("SELECT * FROM recording_clips WHERE CLIP_ID=:clipId")
	suspend fun clipById(clipId: Long): RecordingClipEntity?

	@Query("DELETE FROM recording_clips WHERE CLIP_ID=:clipId")
	suspend fun deleteById(clipId: Long)

	@Query("DELETE FROM recording_clips WHERE SOURCE_AUDIO_ID IN (:audioIds)")
	suspend fun deleteBySourceIds(audioIds: List<Long>)
}
