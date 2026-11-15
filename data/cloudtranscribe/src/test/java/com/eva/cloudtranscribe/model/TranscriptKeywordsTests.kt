package com.eva.cloudtranscribe.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptKeywordsTests {

	@Test
	fun repeated_terms_are_picked_and_single_mentions_are_not() {
		val k = TranscriptKeywords.extract(listOf("這次討論報價單。", "報價單要在週五前寄出。", "請 Amy 確認報價單", "天氣很好"))
		assertEquals("報價單", k.first())
		assertFalse(k.any { it.contains("天氣") })
	}

	@Test
	fun fillers_and_pure_numbers_are_excluded() {
		val k = TranscriptKeywords.extract(listOf("我們就是然後這個", "我們就是然後這個", "2024 2024 100 100", "the the and and"))
		assertTrue(k.toString(), k.isEmpty())
	}

	@Test
	fun shorter_term_covered_by_a_longer_one_is_dropped() {
		val k = TranscriptKeywords.extract(listOf("預算上限", "預算上限", "預算上限"))
		assertEquals(listOf("預算上限"), k)
	}

	@Test
	fun shorter_term_said_often_on_its_own_is_kept() {
		val k = TranscriptKeywords.extract(listOf("報價單", "報價單", "報價", "報價", "報價", "報價"))
		assertTrue(k.toString(), "報價" in k)
	}

	@Test
	fun english_is_case_insensitive_and_keeps_first_spelling() {
		val k = TranscriptKeywords.extract(listOf("The API is slow", "api timeout again", "Api retry", "Q3 plan", "Q3 deck"))
		assertEquals("API", k.first())
		assertTrue("Q3" in k)
		assertEquals(1, k.count { it.equals("api", ignoreCase = true) })
	}

	@Test
	fun at_most_eight_and_ties_keep_first_appearance_order() {
		val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india", "juliet")
		val k = TranscriptKeywords.extract(listOf(words.joinToString(" "), words.joinToString(" ")))
		assertEquals(words.take(8), k)
	}

	@Test
	fun content_words_with_common_characters_survive() {
		val k = TranscriptKeywords.extract(listOf("下午開會議", "會議延後", "會議室訂好了"))
		assertTrue(k.toString(), "會議" in k)
	}

	@Test
	fun empty_and_huge_inputs_are_safe() {
		assertTrue(TranscriptKeywords.extract(emptyList()).isEmpty())
		val k = TranscriptKeywords.extract(List(5000) { "Kubernetes 叢集升級 " + "字".repeat(100) })
		assertTrue(k.toString(), "Kubernetes" in k)
	}
}
