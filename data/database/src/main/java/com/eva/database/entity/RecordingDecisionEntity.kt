package com.eva.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.eva.database.DataBaseConstants

/**
 * One decision / agreement of a recording (separate from action items). [startMs] is only ever copied from a real
 * transcript sentence the model referenced by index; it is null when the transcript has no real timing or the item was
 * added by hand. [source] is `extracted` or `manual`.
 */
@Entity(
	tableName = DataBaseConstants.RECORDING_DECISIONS_TABLE,
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
data class RecordingDecisionEntity(

	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "DECISION_ID")
	val decisionId: Long? = null,

	@ColumnInfo(name = "RECORDING_ID")
	val recordingId: Long,

	@ColumnInfo(name = "TEXT")
	val text: String,

	@ColumnInfo(name = "START_MS")
	val startMs: Long? = null,

	@ColumnInfo(name = "SOURCE_SENTENCE_INDEX")
	val sourceSentenceIndex: Int? = null,

	@ColumnInfo(name = "SOURCE")
	val source: String,

	@ColumnInfo(name = "CREATED_AT")
	val createdAtMillis: Long,

	/** Transcript the extracted decisions came from (id + update time); null for manual items. */
	@ColumnInfo(name = "TRANSCRIPT_REVISION")
	val transcriptRevision: String? = null,
)
