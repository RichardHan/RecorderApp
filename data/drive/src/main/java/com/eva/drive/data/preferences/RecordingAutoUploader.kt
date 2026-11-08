package com.eva.drive.data.preferences

import android.util.Log
import com.eva.drive.domain.models.DriveUploadStatus
import com.eva.drive.domain.provider.RecordingUploadTask
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Boundary used by recorder: cancellation never calls enqueue. */
class RecordingAutoUploader @Inject constructor(
	private val uploadTask: RecordingUploadTask,
) {
	suspend fun enqueue(recordingId: Long) {
		try {
			uploadTask.enqueue(recordingId)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.e(TAG, "Could not enqueue Google Drive upload", e)
		}
	}

	/** Run non-interactively; only explicit user authorization happens in Settings. */
	suspend fun upload(recordingId: Long, accessToken: String): Result<DriveUploadStatus> =
		uploadTask.upload(recordingId, accessToken).fold(
			onSuccess = { Result.success(DriveUploadStatus.SUCCESS) },
			onFailure = { error ->
				when (error) {
					is CancellationException -> throw error
					is com.eva.drive.domain.auth.DriveAuthorizationRequiredException,
					is java.io.FileNotFoundException,
					is SecurityException,
					is IllegalStateException,
					is IllegalArgumentException,
					-> fail(recordingId, error)
					is com.eva.drive.domain.provider.DriveUploadException -> {
						val status = error.httpStatusCode
						if (status == 400 || status == 401 || status == 403 || status == 404) {
							fail(recordingId, error)
						} else Result.success(DriveUploadStatus.PENDING)
					}
					else -> Result.success(DriveUploadStatus.PENDING)
				}
			},
		)

	private suspend fun fail(recordingId: Long, error: Throwable): Result<DriveUploadStatus> {
		uploadTask.markFailed(recordingId, error.message)
		return Result.success(DriveUploadStatus.FAILED)
	}

	private companion object {
		const val TAG = "RECORDING_AUTO_UPLOADER"
	}
}
