package com.eva.feature_recordings.search.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAcrossTests {
	@Test
	fun candidates_put_hits_first_and_dedupe() {
		val c = AskAcross.candidates(listOf(3L to "C", 1L to "A", 3L to "C"), listOf(1L to "A", 2L to "B"))
		assertEquals(listOf(3L, 1L, 2L), c.map { it.first })
	}

	@Test
	fun pick_skips_recordings_without_transcript_and_caps_count() {
		val cands = (1L..10L).map { it to "R$it" }
		val picked = AskAcross.pick(cands) { id -> if (id == 2L) null else if (id == 3L) "  " else "text$id" }
		assertEquals(AskAcross.MAX_SOURCES, picked.size)
		assertEquals(listOf(1L, 4L, 5L, 6L, 7L, 8L), picked.map { it.recordingId })
		assertEquals("錄音", AskAcross.pick(listOf(9L to " ")) { "x" }.single().name)
	}

	@Test
	fun budget_splits_evenly_and_marks_truncation() {
		val long = AskSource(1, "A", "字".repeat(AskAcross.MAX_CHARS))
		val short = AskSource(2, "B", "短")
		val sent = AskAcross.budgeted(listOf(long, short))
		assertEquals("字".repeat(AskAcross.MAX_CHARS / 2) + AskAcross.TRUNCATED, sent[0])
		assertEquals("短", sent[1])
		val p = AskAcross.prompt(" 報價多少？ ", listOf(short))
		assertTrue(p.contains("=== [R1] B ===\n短"))
		assertTrue(p.endsWith("問題：報價多少？"))
		assertTrue(p.contains(AskAcross.NOT_FOUND))
	}

	@Test
	fun cited_parses_brackets_ignores_out_of_range_and_keeps_order() {
		assertEquals(listOf(1, 0), AskAcross.cited("報價 50 萬 [R2]，交期 [R1][R2]，另見 [R9]", 3))
		assertEquals(listOf(0, 2), AskAcross.cited("見 [R1, R3] 與 [R1、3]", 3))
		assertEquals(emptyList<Int>(), AskAcross.cited(AskAcross.NOT_FOUND, 3))
	}
}
