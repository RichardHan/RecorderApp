package com.eva.drive.data.repository

import android.content.Context
import androidx.core.net.toUri
import com.eva.drive.domain.provider.DriveRecording
import com.eva.recordings.domain.provider.VoiceRecordingsProvider
import com.eva.utils.Resource
import com.google.api.client.http.InputStreamContent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

internal class DriveRecordingsProvider @Inject constructor(
	@ApplicationContext private val context: Context,
	private val recordingsProvider: VoiceRecordingsProvider,
) {
	suspend fun recording(recordingId: Long): Result<DriveRecording> = withContext(Dispatchers.IO) {
		try {
			when (val resource = recordingsProvider.getVoiceRecordingAsResourceFromId(recordingId)) {
				is Resource.Error -> Result.failure(resource.error)
				is Resource.Success -> {
					val recording = resource.data
					val stream = context.contentResolver.openInputStream(recording.fileUri.toUri())
						?: throw FileNotFoundException("Cannot open the recorded audio")
					val media = InputStreamContent(recording.mimeType, stream).apply {
						closeInputStream = true
						if (recording.sizeInBytes > 0) length = recording.sizeInBytes
					}
					Result.success(
						DriveRecording(
							recordingId = recordingId,
							fileName = recording.displayName.ifBlank { recording.title },
							mimeType = recording.mimeType,
							sizeBytes = recording.sizeInBytes,
							media = media,
						),
					)
				}
				else -> Result.failure(FileNotFoundException("Recording is unavailable"))
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Result.failure(e)
		}
	}
}
