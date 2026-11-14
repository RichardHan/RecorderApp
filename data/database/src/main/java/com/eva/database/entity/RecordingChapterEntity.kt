package com.eva.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.eva.database.DataBaseConstants

/**
 * One chapter of a recording. [startMs] is always copied from a real transcript segment — the model only picks a
 * sentence index, so a chapter time can never be invented. [transcriptRevision] lets the UI mark chapters as possibly
 * stale after the transcript was re-transcribed.
 */
@Entity(
	tableName = DataBaseConstants.RECORDING_CHAPTERS_TABLE,
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
data class RecordingChapterEntity(

	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "CHAPTER_ID")
	val chapterId: Long? = null,

	@ColumnInfo(name = "RECORDING_ID")
	val recordingId: Long,

	@ColumnInfo(name = "TITLE")
	val title: String,

	@ColumnInfo(name = "START_MS")
	val startMs: Long,

	@ColumnInfo(name = "SOURCE_SENTENCE_INDEX")
	val sourceSentenceIndex: Int,

	@ColumnInfo(name = "CREATED_AT")
	val createdAtMillis: Long,

	/** Identity of the transcript the chapters were derived from (id + update time). */
	@ColumnInfo(name = "TRANSCRIPT_REVISION")
	val transcriptRevision: String,
)
