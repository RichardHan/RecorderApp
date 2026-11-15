package com.eva.database.entity

import androidx.room3.*

@Entity(tableName = "recording_photos", foreignKeys = [ForeignKey(
	entity = RecordingsMetaDataEntity::class, parentColumns = ["RECORDING_ID"],
	childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("recordingId"), Index("owner")])
data class RecordingPhotoEntity(
	@PrimaryKey val id: String,
	val owner: String,
	val recordingId: Long?,
	val startMs: Long,
	val relativePath: String,
	val caption: String,
	val createdAt: Long,
)

@Entity(tableName = "photo_sessions", foreignKeys = [ForeignKey(
	entity = RecordingsMetaDataEntity::class, parentColumns = ["RECORDING_ID"],
	childColumns = ["recordingId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("recordingId")])
data class PhotoSessionEntity(@PrimaryKey val owner: String, val recordingId: Long? = null)
