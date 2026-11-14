package com.eva.drive.data.network

import com.eva.drive.domain.provider.DriveRecording
import com.eva.drive.domain.provider.DriveUploadedFile
import com.eva.drive.domain.provider.DriveUploader
import com.eva.drive.domain.provider.DriveUploadException
import com.eva.drive.domain.provider.DriveClientFactory
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import java.io.IOException
import javax.inject.Inject

internal class DriveUploaderImpl @Inject constructor(
	private val clientFactory: DriveClientFactory,
) : DriveUploader {
	override fun upload(
		accessToken: String,
		targetFolderId: String?,
		preferredFolderName: String,
		recording: DriveRecording,
	): DriveUploadedFile {
		var resolvedFolderId: String? = targetFolderId
		var generatedId: String? = null
		try {
			val drive = Drive.Builder(
				clientFactory.transport,
				clientFactory.jsonFactory,
				clientFactory.requestInitializer(accessToken),
			).setApplicationName(APPLICATION_NAME).build()
			val folderId = targetFolderId ?: findOrCreateFolder(drive, preferredFolderName)
			resolvedFolderId = folderId
			val existing = findExistingRecording(drive, recording.recordingId, folderId)
			if (existing?.id != null) {
				return DriveUploadedFile(existing.id, folderId)
			}

			// A pre-generated ID makes a timeout retry safe: Drive returns 409 instead of a duplicate.
			val newFileId = drive.files().generateIds().setCount(1).execute().ids?.firstOrNull()
				?: throw DriveUploadException("Drive did not provide an upload file ID")
			generatedId = newFileId
			val metadata = File().apply {
				id = newFileId
				name = recording.fileName
				parents = listOf(folderId)
				description = recordingDescription(recording.recordingId)
				appProperties = mapOf(RECORDING_ID_PROPERTY to recording.recordingId.toString())
			}
			val uploadedId = drive.files().create(metadata, recording.media).apply {
				fields = "id,name,parents,appProperties"
				mediaHttpUploader.isDirectUploadEnabled = false
			}.execute().also { result ->
				check(!result.id.isNullOrBlank()) { "Drive returned no file ID" }
			}.id
			return DriveUploadedFile(uploadedId, folderId)
		} catch (e: DriveUploadException) {
			throw e
		} catch (e: GoogleJsonResponseException) {
			if (e.statusCode == 409 && generatedId != null && resolvedFolderId != null) {
				return DriveUploadedFile(generatedId, resolvedFolderId)
			}
			throw e.asDriveUploadException()
		} catch (e: HttpResponseException) {
			if (e.statusCode == 409 && generatedId != null && resolvedFolderId != null) {
				return DriveUploadedFile(generatedId, resolvedFolderId)
			}
			throw DriveUploadException(
				message = e.statusMessage ?: e.message ?: "Drive responded with HTTP ${e.statusCode}",
				cause = e,
				httpStatusCode = e.statusCode,
			)
		} catch (e: IOException) {
			throw DriveUploadException("Audio upload was interrupted; WorkManager can retry", e)
		} finally {
			try {
				recording.media.inputStream.close()
			} catch (_: IOException) {
			}
		}
	}

	private fun findExistingRecording(drive: Drive, recordingId: Long, folderId: String): File? =
		drive.files().list().apply {
			q = "'${folderId.escapeDriveQuery()}' in parents and " +
				"appProperties has { key='$RECORDING_ID_PROPERTY' and value='$recordingId' } and " +
				"trashed = false"
			spaces = "drive"
			fields = "files(id,name,parents,appProperties)"
			pageSize = 1
		}.execute().files?.firstOrNull()

	private fun findOrCreateFolder(drive: Drive, name: String): String {
		val query = "name = '${name.escapeDriveQuery()}' and " +
			"mimeType = 'application/vnd.google-apps.folder' and trashed = false"
		val existing = drive.files().list().apply {
			q = query
			spaces = "drive"
			fields = "files(id,name)"
			pageSize = 1
		}.execute().files?.firstOrNull()
		if (existing?.id != null) return existing.id
		return drive.files().create(
			File().apply {
				this.name = name
				mimeType = "application/vnd.google-apps.folder"
			},
		).setFields("id").execute().id ?: throw DriveUploadException("Cannot create Drive folder")
	}

	private fun GoogleJsonResponseException.asDriveUploadException(): DriveUploadException {
		val reasons = details?.errors
			?.mapNotNull { it.reason?.takeIf(String::isNotBlank) }
			?.toSet()
			.orEmpty()
		return DriveUploadException(
			message = details?.message ?: message ?: "Drive responded with HTTP $statusCode",
			cause = this,
			httpStatusCode = statusCode,
			errorReason = reasons.firstOrNull(),
			errorReasons = reasons,
		)
	}

	private fun String.escapeDriveQuery(): String = replace("\\", "\\\\").replace("'", "\\'")

	private companion object {
		const val APPLICATION_NAME = "RecorderApp"
		const val RECORDING_ID_PROPERTY = "recorderAppRecordingId"
		fun recordingDescription(recordingId: Long) = "RecorderApp recording id: $recordingId"
	}
}
