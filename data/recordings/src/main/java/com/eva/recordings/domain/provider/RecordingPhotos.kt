package com.eva.recordings.domain.provider

import android.net.Uri
import com.eva.recordings.domain.models.RecordingPhoto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface RecordingPhotos {
	val activeOwner: StateFlow<String?>
	suspend fun beginSession()
	suspend fun finishSession(recordingId: Long)
	suspend fun cancelSession()
	suspend fun prepareRecording(recordingId: Long)
	fun observe(owner: String): Flow<List<RecordingPhoto>>
	suspend fun add(owner: String, capturedAtMs: Long, uri: Uri, caption: String)
	suspend fun caption(id: String, caption: String)
	suspend fun pruneDeletedFiles()
	suspend fun delete(id: String)
}
