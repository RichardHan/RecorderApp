package com.eva.feature_player.cloud

import com.eva.cloudtranscribe.model.*
import com.eva.cloudtranscribe.store.TranscriptStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SavedTranscriptEditViewModelTest {
	@get:Rule val folder = TemporaryFolder()
	@Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())
	@After fun teardown() = Dispatchers.resetMain()

	private val record = TranscriptRecord("run", 3, TranscriptSource.OFFLINE, Provenance("offline", model = "m"), 1,
		updatedAtMillis = 2, status = RunStatus.SUCCESS, text = "x",
		segments = listOf(SegmentResult(0, 0, 900, SegmentStatus.OK, "王小明說"), SegmentResult(1, 900, 2000, SegmentStatus.OK, "下週上線")))

	private fun SavedTranscriptViewModel.awaitEntry(predicate: (SavedTranscriptEntry) -> Boolean) = runBlocking {
		withTimeout(5_000) { records.first { list -> list?.singleOrNull()?.let(predicate) == true } }!!.single()
	}

	@Test fun savingALineStoresAnOverlayAndKeepsTheOriginalAndItsTimes() {
		val store = TranscriptStore(folder.root).apply { saveTranscript(record) }
		val vm = SavedTranscriptViewModel(store)
		vm.load(3)
		val entry = vm.awaitEntry { true }
		vm.saveLine(entry, "s0", "王曉明說")
		val edited = vm.awaitEntry { it.editedCount == 1 }
		assertEquals(listOf("王曉明說", "下週上線"), edited.lines.map { it.line.text })
		assertEquals(listOf(0L, 900L), edited.lines.map { it.line.startMs })
		assertEquals(record, store.transcript(3, "run"))
		assertTrue(edited.canUndo)

		vm.undo(edited)
		val undone = vm.awaitEntry { it.editedCount == 0 }
		assertEquals("王小明說", undone.lines.first().line.text)
	}

	@Test fun originalChangedInBackgroundIsNeverOverwrittenAndShowsAConflict() {
		val store = TranscriptStore(folder.root).apply { saveTranscript(record) }
		val vm = SavedTranscriptViewModel(store)
		vm.load(3)
		val entry = vm.awaitEntry { true }
		vm.saveLine(entry, "s0", "王曉明說")
		val edited = vm.awaitEntry { it.editedCount == 1 }

		store.saveTranscript(record.copy(updatedAtMillis = 50))
		vm.saveLine(edited, "s1", "下週一上線")
		runBlocking { withTimeout(5_000) { vm.message.first { it?.contains("原文剛剛更新") == true } } }

		vm.load(3, force = true)
		val conflict = vm.awaitEntry { it.conflict }
		assertEquals("王小明說", conflict.lines.first().line.text)
		vm.resolveConflict(conflict, keep = false)
		vm.awaitEntry { !it.conflict && it.edits == null }
	}

	@Test fun runningTranscriptIsReadOnly() {
		val store = TranscriptStore(folder.root).apply { saveTranscript(record.copy(status = RunStatus.RUNNING)) }
		val vm = SavedTranscriptViewModel(store)
		vm.load(3)
		val entry = vm.awaitEntry { true }
		vm.saveLine(entry, "s0", "改")
		runBlocking { withTimeout(5_000) { vm.message.first { it?.contains("仍在轉錄") == true } } }
		assertNull(store.edits(3, "run"))
	}
}
