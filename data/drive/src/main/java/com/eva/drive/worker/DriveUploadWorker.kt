package com.eva.drive.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eva.drive.domain.auth.DriveAuthorizationRequiredException
import com.eva.drive.domain.auth.GoogleDriveAuth
import com.eva.drive.domain.provider.DriveUploadException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException

@HiltWorker
class DriveUploadWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val auth: GoogleDriveAuth,
	private val uploadTask: com.eva.drive.data.preferences.RecordingAutoUploader,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val recordingId = inputData.getLong(RECORDING_ID, MISSING_ID)
		if (recordingId == MISSING_ID) return Result.failure()
		return try {
			val token = auth.accessToken().getOrElse { error ->
				Log.w(TAG, "Google authorization is unavailable", error)
				return when (error) {
					is DriveAuthorizationRequiredException -> Result.failure()
					else -> Result.retry()
				}
			}
			when (uploadTask.upload(recordingId, token).getOrThrow()) {
				com.eva.drive.domain.models.DriveUploadStatus.SUCCESS -> Result.success()
				com.eva.drive.domain.models.DriveUploadStatus.FAILED -> Result.failure()
				com.eva.drive.domain.models.DriveUploadStatus.PENDING -> uploadError(null, token)
				else -> Result.retry()
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			uploadError(e, null)
		}
	}

	private suspend fun uploadError(error: Throwable?, token: String?): Result {
		if (error != null) Log.w(TAG, "Drive upload failed", error)
		val httpStatus = (error as? DriveUploadException)?.httpStatusCode
		return when {
			httpStatus == 401 && token != null && runAttemptCount == 0 -> {
				auth.invalidateAccessToken(token)
				Result.retry()
			}
			httpStatus == 400 || httpStatus == 401 || httpStatus == 403 || httpStatus == 404 ->
				Result.failure()
			error is DriveAuthorizationRequiredException ||
				error is SecurityException ||
				error is FileNotFoundException ||
				error is IllegalStateException ||
				error is IllegalArgumentException -> Result.failure()
			else -> Result.retry()
		}
	}

	companion object {
		const val RECORDING_ID = "recording_id"
		const val WORK_TAG = "google_drive_upload"
		private const val MISSING_ID = -1L
			private const val TAG = "DRIVE_UPLOAD_WORKER"
		private val TERMINAL_HTTP_CODES = intArrayOf(400, 401, 403, 404)
	}
}
