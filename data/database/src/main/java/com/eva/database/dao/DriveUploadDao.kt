package com.eva.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.eva.database.DataBaseConstants
import com.eva.database.entity.DriveUploadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DriveUploadDao {
	@Upsert
	suspend fun upsert(entity: DriveUploadEntity)

	@Query("SELECT * FROM ${DataBaseConstants.DRIVE_UPLOAD_TABLE} WHERE RECORDING_ID = :recordingId")
	suspend fun getUploadFromId(recordingId: Long): DriveUploadEntity?

	@Query("SELECT * FROM ${DataBaseConstants.DRIVE_UPLOAD_TABLE} WHERE RECORDING_ID = :recordingId")
	fun getUploadFromIdAsFlow(recordingId: Long): Flow<DriveUploadEntity?>

	@Query("SELECT * FROM ${DataBaseConstants.DRIVE_UPLOAD_TABLE} ORDER BY UPDATED_AT DESC")
	fun getUploadsAsFlow(): Flow<List<DriveUploadEntity>>

	@Query("DELETE FROM ${DataBaseConstants.DRIVE_UPLOAD_TABLE} WHERE RECORDING_ID = :recordingId")
	suspend fun remove(recordingId: Long)
}
