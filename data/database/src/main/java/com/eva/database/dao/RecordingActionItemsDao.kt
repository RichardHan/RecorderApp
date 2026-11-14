package com.eva.database.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.Upsert
import com.eva.database.entity.RecordingActionItemEntity
import kotlinx.coroutines.flow.Flow
import org.jetbrains.annotations.VisibleForTesting

@Dao
interface RecordingActionItemsDao {

	@Upsert
	suspend fun insertOrUpdate(item: RecordingActionItemEntity): Long

	@Upsert
	suspend fun insertOrUpdate(items: Collection<RecordingActionItemEntity>)

	@Query("SELECT * FROM recording_action_items WHERE RECORDING_ID=:recordingId ORDER BY IS_DONE ASC, CREATED_AT ASC")
	fun actionItemsAsFlow(recordingId: Long): Flow<List<RecordingActionItemEntity>>

	@Query("SELECT * FROM recording_action_items WHERE RECORDING_ID=:recordingId ORDER BY IS_DONE ASC, CREATED_AT ASC")
	suspend fun actionItems(recordingId: Long): List<RecordingActionItemEntity>

	@Delete
	suspend fun delete(item: RecordingActionItemEntity)

	@Query("DELETE FROM recording_action_items WHERE RECORDING_ID=:recordingId")
	suspend fun deleteByRecordingId(recordingId: Long)

	@VisibleForTesting
	@Query("DELETE FROM recording_action_items")
	suspend fun clearAllActionItems()
}
