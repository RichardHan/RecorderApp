package com.eva.cloudtranscribe.decisions

import com.eva.cloudtranscribe.model.PlaybackLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionPlannerTests {

	private val timed = DecisionPlanner.sentences(
		listOf(
			PlaybackLine("先看報價", 0, 4_000),
			PlaybackLine("  ", 4_000, 5_000),
			PlaybackLine("好，我們決定用 A 方案", 5_000, 9_000),
			PlaybackLine("下週三前小王寄合約", 9_000, 12_000),
		),
	)

	@Test
	fun sentences_keep_original_indices_and_skip_blank_lines() {
		assertEquals(listOf(0, 2, 3), timed.map { it.index })
		assertEquals(5_000L, timed[1].startMs)
	}

	@Test
	fun prompt_numbers_sentences_and_forbids_times_and_speakers() {
		val prompt = DecisionPlanner.prompt(timed)
		assertTrue(prompt.contains("[2] 好，我們決定用 A 方案"))
		assertTrue(prompt.contains("不要輸出任何時間") && prompt.contains("說話者"))
		assertTrue(prompt.contains("[]"))
	}

	@Test
	fun index_maps_to_real_local_time() {
		val drafts = DecisionPlanner.parse("```json\n[{\"text\":\"採用 A 方案\",\"start_sentence_index\":2}]\n```", timed)!!
		assertEquals(listOf(DecisionDraft("採用 A 方案", 2, 5_000L)), drafts)
	}

	@Test
	fun missing_index_or_untimed_transcript_keeps_text_without_time() {
		val untimed = DecisionPlanner.sentences(listOf(PlaybackLine("我們決定延後上線")))
		assertEquals(listOf(DecisionDraft("延後上線", 0, null)), DecisionPlanner.parse("[{\"text\":\"延後上線\",\"start_sentence_index\":0}]", untimed))
		assertEquals(listOf(DecisionDraft("採用 A", null, null)), DecisionPlanner.parse("[{\"text\":\"採用 A\"}]", timed))
		assertEquals(listOf(DecisionDraft("採用 A", null, null)), DecisionPlanner.parse("[{\"text\":\"採用 A\",\"start_sentence_index\":null}]", timed))
	}

	@Test
	fun empty_array_means_no_decisions() {
		assertEquals(emptyList<DecisionDraft>(), DecisionPlanner.parse("[]", timed))
	}

	@Test
	fun broken_answers_reject_the_whole_batch() {
		listOf(
			"不是 JSON",
			"[{\"text\":\"\"}]",
			"[{\"start_sentence_index\":2}]",
			"[{\"text\":123}]",
			"[\"採用 A\"]",
			"[{\"text\":\"採用 A\",\"start_sentence_index\":1}]", // blank line index is not a sentence
			"[{\"text\":\"採用 A\",\"start_sentence_index\":99}]",
			"[{\"text\":\"採用 A\",\"start_sentence_index\":\"x\"}]",
			"[{\"text\":\"採用 A\",\"start_ms\":5000}]",
			"[{\"text\":\"採用 A\",\"speaker\":\"小王\"}]",
			"[{\"text\":\"採用 A\",\"time\":\"00:05\"}]",
			"[" + List(DecisionPlanner.MAX_DECISIONS + 1) { "{\"text\":\"d$it\"}" }.joinToString(",") + "]",
		).forEach { answer -> assertNull(answer, DecisionPlanner.parse(answer, timed)) }
	}

	@Test
	fun duplicates_are_dropped_and_text_is_capped() {
		val long = "長".repeat(300)
		val drafts = DecisionPlanner.parse("[{\"text\":\"採用  A\"},{\"text\":\"採用 a\"},{\"text\":\"$long\"}]", timed)!!
		assertEquals(2, drafts.size)
		assertEquals("採用 A", drafts[0].text)
		assertEquals(DecisionPlanner.MAX_TEXT_CHARS, drafts[1].text.length)
	}
}
