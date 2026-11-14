package com.eva.drive.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eva.drive.data.preferences.DriveUploadOutcome
import com.eva.drive.data.preferences.RecordingAutoUploader
import com.eva.drive.domain.auth.DriveAuthorizationRequiredException
import com.eva.drive.domain.auth.GoogleDriveAuth
import com.eva.drive.domain.provider.DriveUploadException
import com.eva.drive.domain.provider.DriveUploadMeteredNetworkException
import com.eva.drive.domain.provider.DriveUploadOptedOutException
import com.eva.drive.domain.provider.DriveUploadPrecheck
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException

@HiltWorker
class DriveUploadWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val auth: GoogleDriveAuth,
	private val uploadTask: RecordingAutoUploader,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val recordingId = inputData.getLong(RECORDING_ID, MISSING_ID)
		if (recordingId == MISSING_ID) return Result.failure()
		return try {
			when (uploadTask.evaluatePreconditions(recordingId, id, network)) {
				DriveUploadPrecheck.OPTED_OUT -> return Result.success()
				DriveUploadPrecheck.WAIT_FOR_UNMETERED -> return Result.retry()
				DriveUploadPrecheck.ALLOWED -> Unit
			}

			val token = auth.accessToken().getOrElse { error ->
				Log.w(TAG, "Google authorization is unavailable", error)
				return when (error) {
					is DriveAuthorizationRequiredException -> {
						if (uploadTask.evaluatePreconditions(recordingId, id, network) == DriveUploadPrecheck.OPTED_OUT) {
							Result.success()
						} else {
							uploadTask.markFailed(recordingId, error.message)
							Result.failure()
						}
					}
					else -> Result.retry()
				}
			}

			val canRetryUnauthorized = runAttemptCount == 0
			when (
				uploadTask.uploadForWorker(
					recordingId = recordingId,
					accessToken = token,
					canRetryUnauthorized = canRetryUnauthorized,
					workId = id,
					network = network,
				)
			) {
				DriveUploadOutcome.Success -> Result.success()
				DriveUploadOutcome.OptedOut -> Result.success()
				DriveUploadOutcome.RetryWithInvalidatedToken -> {
					invalidateTokenSafely(token)
					Result.retry()
				}
				DriveUploadOutcome.RetryLater -> Result.retry()
				DriveUploadOutcome.Failed -> Result.failure()
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			uploadError(e, null)
		}
	}

	private suspend fun uploadError(error: Throwable?, token: String?): Result {
		if (error != null) Log.w(TAG, "Drive upload failed", error)
		val driveError = error as? DriveUploadException
		val canRetryUnauthorized = token != null && runAttemptCount == 0
		return when {
			error is DriveUploadOptedOutException -> Result.success()
			error is DriveUploadMeteredNetworkException -> Result.retry()
			driveError != null && driveError.httpStatusCode == 401 && canRetryUnauthorized -> {
				invalidateTokenSafely(token)
				Result.retry()
			}
			driveError != null && driveError.isTerminal(canRetryUnauthorized) -> Result.failure()
			error is DriveAuthorizationRequiredException ||
				error is SecurityException ||
				error is FileNotFoundException ||
				error is IllegalStateException ||
				error is IllegalArgumentException -> Result.failure()
			else -> Result.retry()
		}
	}

	private suspend fun invalidateTokenSafely(token: String) {
		try {
			auth.invalidateAccessToken(token)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Failed to invalidate cached Google access token", e)
		}
	}

	companion object {
		const val RECORDING_ID = "recording_id"
		const val WORK_TAG = "google_drive_upload"
		private const val MISSING_ID = -1L
		private const val TAG = "DRIVE_UPLOAD_WORKER"
	}
}
