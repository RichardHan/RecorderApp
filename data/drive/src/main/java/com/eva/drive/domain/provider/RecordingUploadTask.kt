package com.eva.drive.domain.provider

import com.eva.drive.domain.models.DriveUploadStatus
import kotlinx.coroutines.flow.Flow

data class DriveUploadState(
	val recordingId: Long,
	val status: DriveUploadStatus,
	val error: String? = null,
	val fileId: String? = null,
)

interface RecordingUploadTask {
	fun observe(recordingId: Long): Flow<DriveUploadState?>
	suspend fun enqueue(recordingId: Long)
	suspend fun upload(recordingId: Long, accessToken: String): Result<String>
	suspend fun markFailed(recordingId: Long, message: String?)
}
