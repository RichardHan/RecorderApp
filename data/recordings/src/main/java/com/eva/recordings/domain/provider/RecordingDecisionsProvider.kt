package com.eva.recordings.domain.provider

import com.eva.recordings.domain.models.RecordingDecisionModel
import kotlinx.coroutines.flow.Flow

/**
 * Local decision list of one recording. Extracted decisions are only written after a whole successful run, replacing
 * the previous extracted ones; manually added items are kept.
 */
interface RecordingDecisionsProvider {

	fun decisionsAsFlow(recordingId: Long): Flow<List<RecordingDecisionModel>>

	suspend fun decisions(recordingId: Long): List<RecordingDecisionModel>

	/** Replaces only the `extracted` decisions of [recordingId]; manual ones stay. */
	suspend fun replaceExtracted(recordingId: Long, decisions: List<RecordingDecisionModel>)

	suspend fun addManual(recordingId: Long, text: String, createdAtMillis: Long)

	suspend fun updateText(decision: RecordingDecisionModel, text: String)

	suspend fun delete(decision: RecordingDecisionModel)
}
