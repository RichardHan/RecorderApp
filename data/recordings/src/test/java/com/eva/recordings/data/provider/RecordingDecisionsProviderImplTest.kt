package com.eva.recordings.data.provider

import com.eva.database.dao.RecordingDecisionsDao
import com.eva.database.entity.RecordingDecisionEntity
import com.eva.recordings.domain.models.RecordingDecisionModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

class RecordingDecisionsProviderImplTest {

	private class FakeDao : RecordingDecisionsDao {
		val rows = MutableStateFlow<List<RecordingDecisionEntity>>(emptyList())
		private var nextId = 1L
		override suspend fun insert(decisions: Collection<RecordingDecisionEntity>) { decisions.forEach { insertOrUpdate(it) } }
		override suspend fun insertOrUpdate(decision: RecordingDecisionEntity): Long {
			val id = decision.decisionId ?: nextId++
			rows.value = rows.value.filterNot { it.decisionId == id } + decision.copy(decisionId = id)
			return id
		}
		override fun decisionsAsFlow(recordingId: Long): Flow<List<RecordingDecisionEntity>> = rows.map { list -> list.filter { it.recordingId == recordingId } }
		override suspend fun decisions(recordingId: Long) = rows.value.filter { it.recordingId == recordingId }
		override suspend fun deleteByRecordingAndSource(recordingId: Long, source: String) {
			rows.value = rows.value.filterNot { it.recordingId == recordingId && it.source == source }
		}
		override suspend fun deleteById(decisionId: Long) { rows.value = rows.value.filterNot { it.decisionId == decisionId } }
		override suspend fun clearAllDecisions() { rows.value = emptyList() }
	}

	private fun extracted(text: String, start: Long?) = RecordingDecisionModel(
		id = 0, recordingId = 1, text = text, startMs = start, source = RecordingDecisionModel.SOURCE_EXTRACTED,
		createdAtMillis = 10, transcriptRevision = "t@1",
	)

	@Test
	fun re_extraction_replaces_only_extracted_items_and_keeps_manual() = runTest {
		val dao = FakeDao()
		val provider = RecordingDecisionsProviderImpl(dao)
		provider.addManual(1, "  手動  決議 ", 5)
		provider.replaceExtracted(1, listOf(extracted("舊的", 1_000)))
		provider.replaceExtracted(1, listOf(extracted("新的 A", 2_000), extracted("   ", null), extracted("新的 B", null)))
		val all = provider.decisions(1)
		assertEquals(setOf("手動 決議", "新的 A", "新的 B"), all.map { it.text }.toSet())
		assertEquals(1, all.count { !it.isExtracted })
	}

	@Test
	fun edit_and_delete_and_empty_text_is_ignored() = runTest {
		val dao = FakeDao()
		val provider = RecordingDecisionsProviderImpl(dao)
		provider.addManual(1, " ", 5)
		assertTrue(provider.decisions(1).isEmpty())
		provider.replaceExtracted(1, listOf(extracted("採用 A", 2_000)))
		val item = provider.decisions(1).single()
		provider.updateText(item, "")
		assertEquals("採用 A", provider.decisions(1).single().text)
		provider.updateText(item, "採用 A 方案（修正）")
		val edited = provider.decisions(1).single()
		assertEquals("採用 A 方案（修正）", edited.text)
		assertEquals(2_000L, edited.startMs)
		provider.delete(edited)
		assertTrue(provider.decisions(1).isEmpty())
	}

	@Test
	fun text_is_capped() = runTest {
		val provider = RecordingDecisionsProviderImpl(FakeDao())
		provider.addManual(1, "長".repeat(500), 5)
		assertEquals(RecordingDecisionsProviderImpl.MAX_TEXT, provider.decisions(1).single().text.length)
	}
}
