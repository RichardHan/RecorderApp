package com.eva.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.eva.database.DataBaseConstants

/**
 * A short audio clip exported from one recording. [startMs] / [endMs] are always the user's own selection on the
 * source time line (typed, current position or a bookmark); a model never picks them. The clip file lives in app
 * private storage at [filePath]; the source recording is never modified.
 *
 * No foreign key on purpose: recordings opened without a metadata row can still have clips. Clips are removed
 * together with the recording when it is trashed (see the trash providers).
 */
@Entity(
	tableName = DataBaseConstants.RECORDING_CLIPS_TABLE,
	indices = [Index("SOURCE_AUDIO_ID")],
)
data class RecordingClipEntity(

	@PrimaryKey(autoGenerate = true)
	@ColumnInfo(name = "CLIP_ID")
	val clipId: Long? = null,

	@ColumnInfo(name = "SOURCE_AUDIO_ID")
	val sourceAudioId: Long,

	@ColumnInfo(name = "TITLE")
	val title: String,

	@ColumnInfo(name = "START_MS")
	val startMs: Long,

	@ColumnInfo(name = "END_MS")
	val endMs: Long,

	@ColumnInfo(name = "FILE_PATH")
	val filePath: String,

	@ColumnInfo(name = "MIME_TYPE")
	val mimeType: String,

	@ColumnInfo(name = "CREATED_AT")
	val createdAtMillis: Long,
)
