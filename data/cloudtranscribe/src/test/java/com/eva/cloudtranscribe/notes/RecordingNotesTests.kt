package com.eva.cloudtranscribe.notes

import com.eva.cloudtranscribe.engine.Prompts
import com.eva.cloudtranscribe.store.TranscriptStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingNotesTests {

	private val root: File = kotlin.io.path.createTempDirectory("notes").toFile()
	private val store = TranscriptStore(File(root, "store"))
	private var now = 100L
	private fun draft() = RecordingNotesDraft(File(root, "draft"), store) { now++ }

	@After fun cleanup() { root.deleteRecursively() }

	@Test
	fun recording_a_then_b_keeps_notes_separate() {
		val d = draft()
		d.update("A 的議程")
		d.bindTo(1)
		assertEquals("", d.current())
		d.bindTo(2) // B recorded without notes
		assertEquals("A 的議程", store.notes(1)?.text)
		assertNull(store.notes(2))
	}

	@Test
	fun cancelled_recording_drops_its_draft() {
		val d = draft()
		d.update("取消的")
		d.discard()
		d.bindTo(3)
		assertNull(store.notes(3))
		assertNull(draft().leftover.value)
	}

	@Test
	fun draft_from_a_previous_process_is_parked_not_applied() {
		draft().update("上次沒存的")
		val next = draft() // simulates a new app process
		assertEquals("", next.current())
		assertEquals("上次沒存的", next.leftover.value?.text)
		next.bindTo(4)
		assertNull(store.notes(4))
		next.applyLeftover()
		assertNull(next.leftover.value)
		assertEquals("上次沒存的", next.current())
		next.bindTo(5)
		assertEquals("上次沒存的", store.notes(5)?.text)
		assertNull(draft().leftover.value)
	}

	@Test
	fun leftover_can_be_discarded_and_survives_until_decided() {
		draft().update("x")
		draft() // parked
		val third = draft()
		assertEquals("x", third.leftover.value?.text)
		third.discardLeftover()
		assertNull(draft().leftover.value)
	}

	@Test
	fun per_recording_notes_replace_global_defaults() {
		assertEquals("本場", RecordingNotes.effective(" 本場 ", "全域"))
		assertEquals("全域", RecordingNotes.effective("  ", "全域"))
		assertEquals(RecordingNotes.MAX_CHARS, RecordingNotes.effective("x".repeat(5000), "").length)
	}

	@Test
	fun summary_prompt_is_unchanged_without_notes_and_marked_reference_only_with_them() {
		val base = Prompts.summary("逐字稿內容")
		assertEquals(base, RecordingNotes.withNotes(base, null))
		assertEquals(base, RecordingNotes.withNotes(base, "  "))
		val with = RecordingNotes.withNotes(base, "議程：報價")
		assertTrue(with.contains("只供參考"))
		assertTrue(with.indexOf("議程：報價") < with.indexOf("逐字稿：\n逐字稿內容"))
		assertTrue(with.endsWith("逐字稿：\n逐字稿內容"))
	}

	@Test
	fun notes_round_trip_in_store() {
		store.saveNotes(9, RecordingNote("n", 1))
		assertEquals(RecordingNote("n", 1), store.notes(9))
		store.deleteNotes(9)
		assertNull(store.notes(9))
	}
}
