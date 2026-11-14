package com.eva.recordings.data.provider

import com.eva.database.dao.RecordingChaptersDao
import com.eva.database.entity.RecordingChapterEntity
import com.eva.recordings.domain.models.RecordingChapterModel
import com.eva.recordings.domain.provider.RecordingChaptersProvider
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RecordingChaptersProviderImpl @Inject constructor(
	private val dao: RecordingChaptersDao,
) : RecordingChaptersProvider {

	private fun RecordingChapterEntity.toModel() = RecordingChapterModel(
		id = chapterId ?: 0L,
		recordingId = recordingId,
		title = title,
		startMs = startMs,
		sourceSentenceIndex = sourceSentenceIndex,
		createdAtMillis = createdAtMillis,
		transcriptRevision = transcriptRevision,
	)

	private fun RecordingChapterModel.toEntity() = RecordingChapterEntity(
		chapterId = id.takeIf { it > 0L },
		recordingId = recordingId,
		title = title,
		startMs = startMs,
		sourceSentenceIndex = sourceSentenceIndex,
		createdAtMillis = createdAtMillis,
		transcriptRevision = transcriptRevision,
	)

	override fun chaptersAsFlow(recordingId: Long): Flow<List<RecordingChapterModel>> =
		dao.chaptersAsFlow(recordingId).map { list -> list.map { it.toModel() } }

	override suspend fun chapters(recordingId: Long): List<RecordingChapterModel> =
		dao.chapters(recordingId).map { it.toModel() }

	override suspend fun replaceAll(recordingId: Long, chapters: List<RecordingChapterModel>) {
		dao.deleteByRecordingId(recordingId)
		if (chapters.isEmpty()) return
		dao.insert(chapters.map { it.toEntity().copy(chapterId = null, recordingId = recordingId) })
	}

	override suspend fun rename(chapter: RecordingChapterModel, title: String) {
		val trimmed = title.trim().take(MAX_TITLE)
		if (trimmed.isEmpty()) return
		dao.insertOrUpdate(chapter.copy(title = trimmed).toEntity())
	}

	override suspend fun delete(chapter: RecordingChapterModel) {
		if (chapter.id > 0L) dao.deleteById(chapter.id)
	}

	companion object {
		/** Same limit the generation prompt asks for. */
		const val MAX_TITLE = 20
	}
}
