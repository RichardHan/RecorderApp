package com.eva.recordings.data.provider

import android.util.Log
import com.eva.database.dao.RecordingClipsDao
import com.eva.database.entity.RecordingClipEntity
import com.eva.recordings.domain.models.RecordingClipModel
import com.eva.recordings.domain.provider.RecordingClipsProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "RECORDING_CLIPS"

internal class RecordingClipsProviderImpl(
	private val dao: RecordingClipsDao,
	private val rootDirectory: () -> File,
	private val clock: () -> Long = { System.currentTimeMillis() },
) : RecordingClipsProvider {

	private fun RecordingClipEntity.toModel() = RecordingClipModel(
		id = clipId ?: 0L,
		sourceAudioId = sourceAudioId,
		title = title,
		startMs = startMs,
		endMs = endMs,
		filePath = filePath,
		mimeType = mimeType,
		createdAtMillis = createdAtMillis,
	)

	private fun RecordingClipModel.toEntity() = RecordingClipEntity(
		clipId = id.takeIf { it > 0L },
		sourceAudioId = sourceAudioId,
		title = title,
		startMs = startMs,
		endMs = endMs,
		filePath = filePath,
		mimeType = mimeType,
		createdAtMillis = createdAtMillis,
	)

	override fun clipsAsFlow(audioId: Long): Flow<List<RecordingClipModel>> =
		dao.clipsAsFlow(audioId).map { list -> list.map { it.toModel() } }

	override fun clipsDirectory(audioId: Long): File =
		File(rootDirectory(), audioId.toString()).apply { mkdirs() }

	override suspend fun saveExportedClip(
		audioId: Long,
		title: String,
		startMs: Long,
		endMs: Long,
		mimeType: String,
		exportedFile: File,
	): Result<RecordingClipModel> = withContext(Dispatchers.IO) {
		val createdAt = clock()
		val extension = exportedFile.extension.ifBlank { "m4a" }
		val target = File(clipsDirectory(audioId), "clip_${createdAt}_${startMs}-${endMs}.$extension")
		try {
			if (!exportedFile.exists()) return@withContext Result.failure(IllegalStateException("匯出檔不存在"))
			if (!exportedFile.renameTo(target)) {
				exportedFile.copyTo(target, overwrite = true)
				exportedFile.delete()
			}
			val model = RecordingClipModel(
				id = 0L,
				sourceAudioId = audioId,
				title = cleanTitle(title).ifEmpty { "片段" },
				startMs = startMs,
				endMs = endMs,
				filePath = target.absolutePath,
				mimeType = mimeType,
				createdAtMillis = createdAt,
			)
			val id = dao.insertOrUpdate(model.toEntity())
			Result.success(model.copy(id = id))
		} catch (e: Exception) {
			withContext(NonCancellable) {
				target.delete()
				exportedFile.delete()
			}
			if (e is CancellationException) throw e
			Log.e(TAG, "CANNOT SAVE CLIP", e)
			Result.failure(e)
		}
	}

	override suspend fun rename(clip: RecordingClipModel, title: String) {
		val trimmed = cleanTitle(title)
		if (trimmed.isEmpty() || clip.id <= 0L) return
		dao.insertOrUpdate(clip.copy(title = trimmed).toEntity())
	}

	override suspend fun delete(clip: RecordingClipModel) = withContext(Dispatchers.IO) {
		deleteFileInsideRoot(clip.filePath)
		if (clip.id > 0L) dao.deleteById(clip.id)
	}

	override suspend fun deleteAllFor(audioIds: List<Long>) = withContext(Dispatchers.IO) {
		if (audioIds.isEmpty()) return@withContext
		dao.clipsOf(audioIds).forEach { deleteFileInsideRoot(it.filePath) }
		dao.deleteBySourceIds(audioIds)
		audioIds.forEach { id -> File(rootDirectory(), id.toString()).deleteRecursively() }
	}

	/** Only ever deletes files under the clips root, never the source recording or anything else. */
	private fun deleteFileInsideRoot(path: String) {
		val file = File(path)
		val root = rootDirectory().canonicalFile
		if (!file.canonicalFile.startsWith(root)) {
			Log.w(TAG, "REFUSING TO DELETE OUTSIDE CLIPS DIRECTORY")
			return
		}
		file.delete()
	}

	companion object {
		const val MAX_TITLE = 60
		const val CLIPS_FOLDER = "clips"

		fun cleanTitle(title: String): String = title.replace('\n', ' ').trim().take(MAX_TITLE)
	}
}
