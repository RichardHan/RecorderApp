package com.eva.cloudtranscribe.qa

import com.eva.cloudtranscribe.model.PlaybackLine
import com.eva.cloudtranscribe.store.TranscriptStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingQaTests {

	private val segments = RecordingQa.segments(
		listOf(
			PlaybackLine("大家好，今天討論報價", 0, 5_000),
			PlaybackLine(" ", 5_000, 6_000),
			PlaybackLine("Amy 說報價下週三前交給客戶", 6_000, 12_000),
			PlaybackLine("預算上限是五十萬", 12_000, 15_000),
		),
	)
	private val full = RecordingQa.context(segments)

	@Test
	fun segments_are_numbered_from_one_skipping_blank_lines() {
		assertEquals(listOf(1, 2, 3), segments.map { it.id })
		assertFalse(full.truncated)
		assertNull(full.coverageNote)
	}

	@Test
	fun prompt_contains_rules_question_and_numbered_segments() {
		val prompt = RecordingQa.prompt("報價何時交？", full)
		assertTrue(prompt.contains("問題：報價何時交？"))
		assertTrue(prompt.contains("[S2 00:06–00:12] Amy 說報價下週三前交給客戶"))
		assertTrue(prompt.contains(RecordingQa.NOT_FOUND) && prompt.contains("〔S3,S5〕"))
	}

	@Test
	fun truncation_is_reported_with_the_range_used() {
		val small = RecordingQa.context(segments, maxChars = 70)
		assertTrue(small.truncated)
		assertEquals(2, small.segments.size)
		assertEquals("只用了前 00:12（第 1–2 段，共 3 段）", small.coverageNote)
		assertTrue(RecordingQa.prompt("q", small).contains("只是逐字稿的一部分"))
		val untimed = RecordingQa.context(RecordingQa.segments(listOf(PlaybackLine("a".repeat(50)), PlaybackLine("b".repeat(50)))), maxChars = 60)
		assertEquals("只用了前 1 段（共 2 段）", untimed.coverageNote)
	}

	@Test
	fun matching_citations_are_verified_and_mapped_to_real_time() {
		val parsed = RecordingQa.parse("Amy 說下週三前交報價。〔S2〕預算是五十萬〔S3〕", full)
		assertEquals(listOf(QaCitation(2, 6_000), QaCitation(3, 12_000)), parsed.citations)
		assertEquals(0, parsed.unverified)
		assertFalse(parsed.notFound)
	}

	@Test
	fun multi_citation_format_and_bracket_variants() {
		val parsed = RecordingQa.parse("報價下週三要交〔S1，S2〕", full)
		assertEquals(listOf(1, 2), parsed.citations.map { it.segmentId })
		assertEquals(listOf(2), RecordingQa.parse("Amy 負責 [S2]", full).citations.map { it.segmentId })
	}

	@Test
	fun unknown_or_mismatched_citations_are_not_verified() {
		val unknown = RecordingQa.parse("報價下週三交〔S9〕", full)
		assertTrue(unknown.citations.isEmpty())
		assertEquals(1, unknown.unverified)
		val mismatch = RecordingQa.parse("會議改到台北舉行〔S3〕", full)
		assertTrue(mismatch.citations.isEmpty())
		assertEquals(1, mismatch.unverified)
	}

	@Test
	fun not_found_is_kept_as_is() {
		val parsed = RecordingQa.parse("逐字稿中找不到", full)
		assertTrue(parsed.notFound)
		assertEquals("逐字稿中找不到", parsed.answer)
		val record = QaRecord("q", 1, "t", "q", parsed.answer, notFound = true, createdAtMillis = 0)
		assertFalse(record.sourceUnverified)
		assertTrue(record.copy(notFound = false).sourceUnverified)
	}

	@Test
	fun shares_word_needs_a_real_overlap() {
		assertTrue(RecordingQa.sharesWord("Amy 會處理", "Amy 說報價"))
		assertTrue(RecordingQa.sharesWord("預算多少", "預算上限是五十萬"))
		assertFalse(RecordingQa.sharesWord("我們", "我們開始"))
		assertFalse(RecordingQa.sharesWord("台北", "高雄"))
	}

	@Test
	fun qa_records_round_trip_in_the_store() {
		val dir = kotlin.io.path.createTempDirectory("qa").toFile()
		try {
			val store = TranscriptStore(dir)
			val a = QaRecord("qa-1", 7, "t", "q1", "a1", listOf(QaCitation(2, 6_000)), createdAtMillis = 1)
			val b = a.copy(id = "qa-2", question = "q2", createdAtMillis = 2)
			store.saveQa(a); store.saveQa(b)
			assertEquals(listOf("qa-2", "qa-1"), store.qa(7).map { it.id })
			store.deleteQa(7, "qa-1")
			assertEquals(listOf(b), store.qa(7))
			assertTrue(store.qa(8).isEmpty())
		} finally {
			dir.deleteRecursively()
		}
	}
}
