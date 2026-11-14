package com.eva.recordings.data.provider

import com.eva.database.dao.RecordingDecisionsDao
import com.eva.database.entity.RecordingDecisionEntity
import com.eva.recordings.domain.models.RecordingDecisionModel
import com.eva.recordings.domain.provider.RecordingDecisionsProvider
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RecordingDecisionsProviderImpl @Inject constructor(
	private val dao: RecordingDecisionsDao,
) : RecordingDecisionsProvider {

	private fun RecordingDecisionEntity.toModel() = RecordingDecisionModel(
		id = decisionId ?: 0L,
		recordingId = recordingId,
		text = text,
		startMs = startMs,
		sourceSentenceIndex = sourceSentenceIndex,
		source = source,
		createdAtMillis = createdAtMillis,
		transcriptRevision = transcriptRevision,
	)

	private fun RecordingDecisionModel.toEntity() = RecordingDecisionEntity(
		decisionId = id.takeIf { it > 0L },
		recordingId = recordingId,
		text = text,
		startMs = startMs,
		sourceSentenceIndex = sourceSentenceIndex,
		source = source,
		createdAtMillis = createdAtMillis,
		transcriptRevision = transcriptRevision,
	)

	override fun decisionsAsFlow(recordingId: Long): Flow<List<RecordingDecisionModel>> =
		dao.decisionsAsFlow(recordingId).map { list -> list.map { it.toModel() } }

	override suspend fun decisions(recordingId: Long): List<RecordingDecisionModel> =
		dao.decisions(recordingId).map { it.toModel() }

	override suspend fun replaceExtracted(recordingId: Long, decisions: List<RecordingDecisionModel>) {
		dao.deleteByRecordingAndSource(recordingId, RecordingDecisionModel.SOURCE_EXTRACTED)
		if (decisions.isEmpty()) return
		dao.insert(
			decisions.map {
				it.toEntity().copy(
					decisionId = null,
					recordingId = recordingId,
					text = clean(it.text),
					source = RecordingDecisionModel.SOURCE_EXTRACTED,
				)
			}.filter { it.text.isNotEmpty() },
		)
	}

	override suspend fun addManual(recordingId: Long, text: String, createdAtMillis: Long) {
		val trimmed = clean(text)
		if (trimmed.isEmpty()) return
		dao.insertOrUpdate(
			RecordingDecisionEntity(
				recordingId = recordingId,
				text = trimmed,
				source = RecordingDecisionModel.SOURCE_MANUAL,
				createdAtMillis = createdAtMillis,
			),
		)
	}

	override suspend fun updateText(decision: RecordingDecisionModel, text: String) {
		val trimmed = clean(text)
		if (trimmed.isEmpty() || decision.id <= 0L) return
		dao.insertOrUpdate(decision.copy(text = trimmed).toEntity())
	}

	override suspend fun delete(decision: RecordingDecisionModel) {
		if (decision.id > 0L) dao.deleteById(decision.id)
	}

	companion object {
		const val MAX_TEXT = 200
		fun clean(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT)
	}
}
