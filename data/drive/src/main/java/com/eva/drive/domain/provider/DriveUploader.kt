package com.eva.drive.domain.provider

import com.google.api.client.http.InputStreamContent
import java.io.IOException

data class DriveRecording(
	val recordingId: Long,
	val fileName: String,
	val mimeType: String,
	val sizeBytes: Long,
	val media: InputStreamContent,
)

data class DriveUploadedFile(
	val id: String,
	val folderId: String,
)

interface DriveUploader {
	fun upload(
		accessToken: String,
		targetFolderId: String?,
		preferredFolderName: String,
		recording: DriveRecording,
	): DriveUploadedFile
}

class DriveUploadException(
	message: String,
	cause: Throwable? = null,
	val httpStatusCode: Int? = null,
	val errorReason: String? = null,
	val errorReasons: Set<String> = setOfNotNull(errorReason),
) : IOException(message, cause) {

	val isRateLimitForbidden: Boolean
		get() = httpStatusCode == 403 && errorReasons.any { it in RETRYABLE_FORBIDDEN_REASONS }

	fun isTerminal(canRetryUnauthorized: Boolean): Boolean = when (httpStatusCode) {
		400, 404 -> true
		401 -> !canRetryUnauthorized
		403 -> !isRateLimitForbidden
		else -> false
	}

	companion object {
		val RETRYABLE_FORBIDDEN_REASONS = setOf(
			"rateLimitExceeded",
			"userRateLimitExceeded",
			"sharingRateLimitExceeded",
			"concurrentLimitExceeded",
		)
	}
}
