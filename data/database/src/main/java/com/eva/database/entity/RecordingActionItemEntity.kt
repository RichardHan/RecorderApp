package com.eva.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.eva.database.DataBaseConstants

/**
 * One locally stored to-do item of a recording. [source] is "summary" when the user picked it from an existing
 * summary and "manual" when they typed it; the text is always the user's, never generated here.
 */
@Entity(
	tableName = DataBaseConstants.RECORDING_ACTION_ITEMS_TABLE,
	foreignKeys = [
		ForeignKey(
			entity = RecordingsMetaDataEntity::class,
			childColumns = ["RECORDING_ID"],
			parentColumns = ["RECORDING_ID"],
			onUpdate = ForeignKey.CASCADE,
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [
		Index("RECORDING_ID")
	],
)
data class RecordingActionItemEntity(

	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "ACTION_ITEM_ID")
	val actionItemId: Long? = null,

	@ColumnInfo(name = "RECORDING_ID")
	val recordingId: Long,

	@ColumnInfo(name = "TEXT")
	val text: String,

	@ColumnInfo(name = "IS_DONE")
	val done: Boolean = false,

	@ColumnInfo(name = "CREATED_AT")
	val createdAtMillis: Long,

	/** "summary" or "manual"; kept so the source of every item stays explainable. */
	@ColumnInfo(name = "SOURCE")
	val source: String = SOURCE_MANUAL,
) {
	companion object {
		const val SOURCE_SUMMARY = "summary"
		const val SOURCE_MANUAL = "manual"
	}
}
