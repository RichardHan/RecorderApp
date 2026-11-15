package com.eva.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.eva.database.entity.RecordingDecisionEntity
import kotlinx.coroutines.flow.Flow
import org.jetbrains.annotations.VisibleForTesting

@Dao
interface RecordingDecisionsDao {

	@Upsert
	suspend fun insert(decisions: Collection<RecordingDecisionEntity>)

	@Upsert
	suspend fun insertOrUpdate(decision: RecordingDecisionEntity): Long

	@Query("SELECT * FROM recording_decisions WHERE RECORDING_ID=:recordingId ORDER BY START_MS IS NULL, START_MS ASC, DECISION_ID ASC")
	fun decisionsAsFlow(recordingId: Long): Flow<List<RecordingDecisionEntity>>

	@Query("SELECT * FROM recording_decisions WHERE RECORDING_ID=:recordingId ORDER BY START_MS IS NULL, START_MS ASC, DECISION_ID ASC")
	suspend fun decisions(recordingId: Long): List<RecordingDecisionEntity>

	@Query("DELETE FROM recording_decisions WHERE RECORDING_ID=:recordingId AND SOURCE=:source")
	suspend fun deleteByRecordingAndSource(recordingId: Long, source: String)

	@Query("DELETE FROM recording_decisions WHERE DECISION_ID=:decisionId")
	suspend fun deleteById(decisionId: Long)

	@VisibleForTesting
	@Query("DELETE FROM recording_decisions")
	suspend fun clearAllDecisions()
}
