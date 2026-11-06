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
) : IOException(message, cause)
