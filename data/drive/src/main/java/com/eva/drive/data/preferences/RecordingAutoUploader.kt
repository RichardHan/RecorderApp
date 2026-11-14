package com.eva.drive.data.preferences

import android.net.Network
import android.util.Log
import com.eva.drive.domain.auth.DriveAuthorizationRequiredException
import com.eva.drive.domain.models.DriveUploadStatus
import com.eva.drive.domain.provider.DriveUploadException
import com.eva.drive.domain.provider.DriveUploadMeteredNetworkException
import com.eva.drive.domain.provider.DriveUploadOptedOutException
import com.eva.drive.domain.provider.DriveUploadPrecheck
import com.eva.drive.domain.provider.RecordingUploadTask
import java.io.FileNotFoundException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

sealed interface DriveUploadOutcome {
	data object Success : DriveUploadOutcome
	data object OptedOut : DriveUploadOutcome
	data object RetryLater : DriveUploadOutcome
	data object RetryWithInvalidatedToken : DriveUploadOutcome
	data object Failed : DriveUploadOutcome
}

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

	suspend fun evaluatePreconditions(
		recordingId: Long,
		workId: UUID? = null,
		network: Network? = null,
	): DriveUploadPrecheck = uploadTask.evaluatePreconditions(recordingId, workId, network)

	suspend fun markFailed(recordingId: Long, message: String?) {
		uploadTask.markFailed(recordingId, message)
	}

	/** Run non-interactively; only explicit user authorization happens in Settings. */
	suspend fun upload(
		recordingId: Long,
		accessToken: String,
		canRetryUnauthorized: Boolean = true,
	): Result<DriveUploadStatus> {
		val status = when (
			uploadForWorker(
				recordingId = recordingId,
				accessToken = accessToken,
				canRetryUnauthorized = canRetryUnauthorized,
			)
		) {
			DriveUploadOutcome.Success -> DriveUploadStatus.SUCCESS
			DriveUploadOutcome.Failed -> DriveUploadStatus.FAILED
			DriveUploadOutcome.OptedOut,
			DriveUploadOutcome.RetryLater,
			DriveUploadOutcome.RetryWithInvalidatedToken,
			-> DriveUploadStatus.PENDING
		}
		return Result.success(status)
	}

	suspend fun uploadForWorker(
		recordingId: Long,
		accessToken: String,
		canRetryUnauthorized: Boolean,
		workId: UUID? = null,
		network: Network? = null,
	): DriveUploadOutcome =
		uploadTask.upload(recordingId, accessToken, workId, network).fold(
			onSuccess = { DriveUploadOutcome.Success },
			onFailure = { error ->
				when (error) {
					is CancellationException -> throw error
					is DriveUploadOptedOutException -> DriveUploadOutcome.OptedOut
					is DriveUploadMeteredNetworkException -> DriveUploadOutcome.RetryLater
					is DriveAuthorizationRequiredException,
					is FileNotFoundException,
					is SecurityException,
					is IllegalStateException,
					is IllegalArgumentException,
					-> fail(recordingId, error)
					is DriveUploadException -> when {
						error.httpStatusCode == 401 && canRetryUnauthorized ->
							DriveUploadOutcome.RetryWithInvalidatedToken
						error.isTerminal(canRetryUnauthorized) -> fail(recordingId, error)
						else -> DriveUploadOutcome.RetryLater
					}
					else -> DriveUploadOutcome.RetryLater
				}
			},
		)

	private suspend fun fail(recordingId: Long, error: Throwable): DriveUploadOutcome {
		uploadTask.markFailed(recordingId, error.message)
		return DriveUploadOutcome.Failed
	}

	private companion object {
		const val TAG = "RECORDING_AUTO_UPLOADER"
	}
}
