package com.eva.recordings.data.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.eva.database.dao.RecordingPhotosDao
import com.eva.database.dao.RecordingsMetadataDao
import com.eva.database.entity.*
import com.eva.recordings.domain.models.*
import com.eva.recordings.domain.provider.RecordingPhotos
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Photos live only in noBackupFilesDir; no audio uploader or cloud client receives them. */
@Singleton
class RecordingPhotosImpl internal constructor(
	@ApplicationContext private val context: Context,
	private val dao: RecordingPhotosDao,
	private val metadata: RecordingsMetadataDao,
	private val encode: (Uri, File) -> Unit,
) : RecordingPhotos {
	@Inject constructor(@ApplicationContext context: Context, dao: RecordingPhotosDao, metadata: RecordingsMetadataDao)
		: this(context, dao, metadata, { uri, target -> encodeImage(context, uri, target) })
	internal fun stopCleanup() { scope.cancel() }

	private val root = File(context.noBackupFilesDir, "recording_photos").apply { mkdirs() }
	private val lock = Mutex()
	private val active = MutableStateFlow<String?>(null)
	override val activeOwner: StateFlow<String?> = active.asStateFlow()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	init {
		// CASCADE removes rows on trash/delete. Remove their private files, also after a process restart.
		scope.launch { dao.observeAll().collect { lock.withLock { sweep() } } }
	}
	private suspend fun sweep() {
		val kept = dao.all().map { it.relativePath }.toSet()
		root.listFiles()?.filter { it.name !in kept }?.forEach { it.delete() }
	}
	override suspend fun beginSession() = withContext(Dispatchers.IO) { lock.withLock {
		dao.discardDrafts()
		val owner = UUID.randomUUID().toString()
		dao.session(PhotoSessionEntity(owner))
		active.value = owner
	} }
	override suspend fun prepareRecording(recordingId: Long) {
		metadata.addRecordingMetaDataBulk(listOf(RecordingsMetaDataEntity(recordingId)))
	}
	override suspend fun finishSession(recordingId: Long) = withContext(Dispatchers.IO) { lock.withLock {
		val owner = active.value ?: return@withLock
		prepareRecording(recordingId)
		dao.bind(owner, recordingId)
		active.value = null
	} }
	override suspend fun cancelSession() = withContext(Dispatchers.IO) { lock.withLock {
		active.value?.let { dao.discard(it) }
		active.value = null
		sweep()
	} }
	override fun observe(owner: String): Flow<List<RecordingPhoto>> {
		val id = owner.removePrefix("recording:").toLongOrNull()
		return (if (id != null) dao.observeRecording(id) else dao.observe(owner)).map { rows ->
			rows.map { RecordingPhoto(it.id, it.startMs, File(root, it.relativePath).absolutePath, it.caption) }
		}
	}
	override suspend fun add(owner: String, capturedAtMs: Long, uri: Uri, caption: String) = withContext(Dispatchers.IO) { lock.withLock {
		require(capturedAtMs >= 0)
		val explicitId = owner.takeIf { it.startsWith("recording:") }?.substringAfter(':')?.toLongOrNull()
		val session = if (explicitId == null) dao.session(owner) else null
		check(explicitId != null || session != null) { "session" }
		val id = explicitId ?: session?.recordingId
		check(id != null || active.value == owner) { "session" }
		check(id == null || metadata.getRecordingMetaDataFromId(id) != null) { "session" }
		check(dao.count(owner, id) < PhotoRules.MAX_PHOTOS) { "limit" }
		val name = "${UUID.randomUUID()}.jpg"
		val target = File(root, name)
		try {
			check(root.usableSpace > 8L * 1024 * 1024) { "space" }
			encode(uri, target)
			dao.insert(RecordingPhotoEntity(name.removeSuffix(".jpg"), owner, id, capturedAtMs, name,
				caption.trim().take(PhotoRules.MAX_CAPTION), System.currentTimeMillis()))
		} catch (e: Exception) { target.delete(); throw e }
	} }
	override suspend fun caption(id: String, caption: String) { dao.caption(id, caption.trim().take(PhotoRules.MAX_CAPTION)) }
	override suspend fun pruneDeletedFiles() = withContext(Dispatchers.IO) { lock.withLock { sweep() } }
	override suspend fun delete(id: String) = withContext(Dispatchers.IO) { lock.withLock { dao.delete(id); sweep() } }
}

private fun encodeImage(context: Context, uri: Uri, target: File) {
	val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
		val (w, h) = PhotoRules.dimensions(info.size.width, info.size.height)
		decoder.setTargetSize(w, h)
		decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
	}
	try { target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
	finally { bitmap.recycle() }
}
