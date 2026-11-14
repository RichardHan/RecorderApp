package com.eva.recordings.domain.provider

import com.eva.recordings.domain.models.RecordingActionItemModel
import kotlinx.coroutines.flow.Flow

/** Local to-do list of one recording. Everything stays on the device; nothing is synced anywhere. */
interface RecordingActionItemsProvider {

	fun actionItemsAsFlow(recordingId: Long): Flow<List<RecordingActionItemModel>>

	suspend fun actionItems(recordingId: Long): List<RecordingActionItemModel>

	/**
	 * Adds [texts] as new items, skipping anything already present (compared case/whitespace-insensitively) and blank
	 * lines. Returns the items that were actually inserted.
	 */
	suspend fun addItems(
		recordingId: Long,
		texts: List<String>,
		source: String,
		nowMillis: Long,
	): List<RecordingActionItemModel>

	/** Edits the text and/or the done flag of an existing item; blank text is ignored. */
	suspend fun updateItem(item: RecordingActionItemModel)

	suspend fun deleteItem(item: RecordingActionItemModel)
}
