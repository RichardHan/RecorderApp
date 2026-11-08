package com.eva.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.eva.database.DataBaseConstants

@Entity(tableName = DataBaseConstants.DRIVE_UPLOAD_TABLE)
data class DriveUploadEntity(
	@PrimaryKey
	@ColumnInfo(name = "RECORDING_ID")
	val recordingId: Long,

	@ColumnInfo(name = "STATUS")
	val status: String,

	@ColumnInfo(name = "FILE_ID")
	val fileId: String? = null,

	@ColumnInfo(name = "ERROR_MESSAGE")
	val errorMessage: String? = null,

	@ColumnInfo(name = "UPDATED_AT", defaultValue = "0")
	val updatedAt: Long = 0,
)
