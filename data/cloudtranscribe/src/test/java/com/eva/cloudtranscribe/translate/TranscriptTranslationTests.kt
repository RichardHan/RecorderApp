package com.eva.cloudtranscribe.translate

import com.eva.cloudtranscribe.model.PlaybackLine
import com.eva.cloudtranscribe.store.TranscriptStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTranslationTests {

	private val sentences = TranscriptTranslation.sentences(
		listOf(PlaybackLine("你好", 0, 1_000), PlaybackLine(" ", 1_000, 2_000), PlaybackLine("報價週三交", 2_000, 3_000)),
	)

	@Test
	fun ids_follow_line_indices_and_skip_blank_lines() {
		assertEquals(listOf(1, 3), sentences.map { it.id })
	}

	@Test
	fun prompt_carries_ids_and_forbids_new_sentences() {
		val prompt = TranscriptTranslation.prompt(sentences, TranslationLanguage.EN)
		assertTrue(prompt.contains("{\"id\":3,\"text\":\"報價週三交\"}"))
		assertTrue(prompt.contains("English") && prompt.contains("不要合併、拆分、刪除或新增"))
	}

	@Test
	fun aligned_answer_is_accepted_in_original_order() {
		val parsed = TranscriptTranslation.parse("```json\n[{\"id\":3,\"translation\":\"Quote due Wed\"},{\"id\":\"1\",\"translation\":\"Hello\"}]\n```", sentences)
		assertEquals(listOf(TranslatedLine(1, "Hello"), TranslatedLine(3, "Quote due Wed")), parsed)
	}

	@Test
	fun missing_extra_duplicate_or_invented_lines_are_rejected() {
		listOf(
			"[{\"id\":1,\"translation\":\"Hello\"}]",
			"[{\"id\":1,\"translation\":\"Hello\"},{\"id\":3,\"translation\":\"Q\"},{\"id\":4,\"translation\":\"invented\"}]",
			"[{\"id\":1,\"translation\":\"Hello\"},{\"id\":1,\"translation\":\"Hi\"},{\"id\":3,\"translation\":\"Q\"}]",
			"[{\"id\":1,\"translation\":\"\"},{\"id\":3,\"translation\":\"Q\"}]",
			"[{\"id\":1,\"translation\":\"Hello\",\"note\":\"x\"},{\"id\":3,\"translation\":\"Q\"}]",
			"not json",
		).forEach { assertNull(it, TranscriptTranslation.parse(it, sentences)) }
	}

	@Test
	fun chunks_split_by_size_and_keep_order() {
		val many = (1..10).map { TranslationSentence(it, "x".repeat(30)) }
		val chunks = TranscriptTranslation.chunks(many, maxChars = 100)
		assertEquals((1..10).toList(), chunks.flatten().map { it.id })
		assertTrue(chunks.size > 1 && chunks.all { it.isNotEmpty() })
		assertEquals(1, TranscriptTranslation.chunks(listOf(TranslationSentence(1, "y".repeat(500))), 100).size)
	}

	@Test
	fun views_read_the_right_fields_and_staleness_uses_revision() {
		assertEquals("原" to null, TranscriptTranslation.display("原", "T", TranslationView.ORIGINAL))
		assertEquals("T" to null, TranscriptTranslation.display("原", "T", TranslationView.TRANSLATION))
		assertEquals("原" to null, TranscriptTranslation.display("原", null, TranslationView.TRANSLATION))
		assertEquals("原" to "T", TranscriptTranslation.display("原", "T", TranslationView.BILINGUAL))
		val saved = SavedTranslation(1, "t", "en", TranscriptTranslation.revisionOf("t", 5), listOf(TranslatedLine(1, "Hello"), TranslatedLine(3, "Q")), 9)
		assertEquals(listOf("Hello", null, "Q"), TranscriptTranslation.aligned(3, saved))
		assertFalse(TranscriptTranslation.isStale(saved, "t", 5))
		assertTrue(TranscriptTranslation.isStale(saved, "t", 6))
	}

	@Test
	fun store_keeps_latest_per_language() {
		val dir = kotlin.io.path.createTempDirectory("tr").toFile()
		try {
			val store = TranscriptStore(dir)
			val en = SavedTranslation(1, "t", "en", "t@1", listOf(TranslatedLine(1, "a")), 1)
			store.saveTranslation(en)
			store.saveTranslation(en.copy(lines = listOf(TranslatedLine(1, "b")), createdAtMillis = 2))
			store.saveTranslation(en.copy(targetLang = "ja", createdAtMillis = 3))
			assertEquals(listOf("ja", "en"), store.translations(1).map { it.targetLang })
			assertEquals("b", store.translations(1).first { it.targetLang == "en" }.lines.single().translation)
			store.deleteTranslation(1, "en")
			assertEquals(listOf("ja"), store.translations(1).map { it.targetLang })
		} finally {
			dir.deleteRecursively()
		}
	}
}
