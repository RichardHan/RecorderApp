package com.eva.recordings.data.provider

import com.eva.database.dao.RecordingActionItemsDao
import com.eva.database.entity.RecordingActionItemEntity
import com.eva.recordings.domain.models.RecordingActionItemModel
import com.eva.recordings.domain.provider.RecordingActionItemsProvider
import com.eva.utils.normalizeForComparison
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RecordingActionItemsProviderImpl @Inject constructor(
	private val dao: RecordingActionItemsDao,
) : RecordingActionItemsProvider {

	private fun RecordingActionItemEntity.toModel() = RecordingActionItemModel(
		id = actionItemId ?: 0L,
		recordingId = recordingId,
		text = text,
		isDone = done,
		createdAtMillis = createdAtMillis,
		source = source,
	)

	override fun actionItemsAsFlow(recordingId: Long): Flow<List<RecordingActionItemModel>> =
		dao.actionItemsAsFlow(recordingId).map { items -> items.map { it.toModel() } }

	override suspend fun actionItems(recordingId: Long): List<RecordingActionItemModel> =
		dao.actionItems(recordingId).map { it.toModel() }

	override suspend fun addItems(
		recordingId: Long,
		texts: List<String>,
		source: String,
		nowMillis: Long,
	): List<RecordingActionItemModel> {
		val existing = dao.actionItems(recordingId).map { it.text }
		val added = mutableListOf<RecordingActionItemModel>()
		texts.map { it.trim() }
			.filter { it.isNotEmpty() }
			.filterNot { candidate -> existing.any { normalizeForComparison(it) == normalizeForComparison(candidate) } }
			.distinctBy { normalizeForComparison(it) }
			.forEachIndexed { index, text ->
				val entity = RecordingActionItemEntity(
					recordingId = recordingId,
					text = text,
					done = false,
					// keep the caller's order for items created in the same batch
					createdAtMillis = nowMillis + index,
					source = source,
				)
				val id = dao.insertOrUpdate(entity)
				added += entity.copy(actionItemId = id).toModel()
			}
		return added
	}

	override suspend fun updateItem(item: RecordingActionItemModel) {
		val text = item.text.trim()
		if (text.isEmpty()) return
		dao.insertOrUpdate(
			RecordingActionItemEntity(
				actionItemId = item.id,
				recordingId = item.recordingId,
				text = text,
				done = item.isDone,
				createdAtMillis = item.createdAtMillis,
				source = item.source,
			),
		)
	}

	override suspend fun deleteItem(item: RecordingActionItemModel) {
		dao.delete(
			RecordingActionItemEntity(
				actionItemId = item.id,
				recordingId = item.recordingId,
				text = item.text,
				done = item.isDone,
				createdAtMillis = item.createdAtMillis,
				source = item.source,
			),
		)
	}
}
