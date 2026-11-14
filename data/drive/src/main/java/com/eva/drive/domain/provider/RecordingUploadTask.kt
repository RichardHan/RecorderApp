package com.eva.drive.domain.provider

import android.net.Network
import com.eva.drive.domain.models.DriveUploadStatus
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class DriveUploadState(
	val recordingId: Long,
	val status: DriveUploadStatus,
	val error: String? = null,
	val fileId: String? = null,
)

enum class DriveUploadPrecheck {
	ALLOWED,
	OPTED_OUT,
	WAIT_FOR_UNMETERED,
}

class DriveUploadOptedOutException : Exception("Google Drive auto-upload is disabled")

class DriveUploadMeteredNetworkException :
	IOException("Waiting for an unmetered network for Google Drive upload")

interface RecordingUploadTask {
	fun observe(recordingId: Long): Flow<DriveUploadState?>
	suspend fun enqueue(recordingId: Long)
	suspend fun evaluatePreconditions(
		recordingId: Long,
		workId: UUID? = null,
		network: Network? = null,
	): DriveUploadPrecheck
	suspend fun upload(
		recordingId: Long,
		accessToken: String,
		workId: UUID? = null,
		network: Network? = null,
	): Result<String>
	suspend fun markFailed(recordingId: Long, message: String?)
}
