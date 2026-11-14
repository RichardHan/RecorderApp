package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.chapters.ChapterPlanner
import com.eva.cloudtranscribe.model.PlaybackLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chapter answers are validated strictly; times always come from real sentences, never from the model. */
class ChapterPlannerTests {

	private val lines = listOf(
		PlaybackLine("開場與自我介紹", 0, 1_000),
		PlaybackLine("報價討論", 60_000, 61_000),
		PlaybackLine("下一步", 120_000, 121_000),
	)

	private val sentences = ChapterPlanner.sentences(lines)

	@Test
	fun onlyTimedSentencesAreUsedAndTheyKeepTheirRealTimes() {
		val untimed = ChapterPlanner.sentences(listOf(PlaybackLine("沒有時間"), lines[1]))
		assertEquals(listOf(1), untimed.map { it.index })
		assertEquals(listOf(60_000L), untimed.map { it.startMs })
		assertFalse(ChapterPlanner.hasTimedSentences(listOf(PlaybackLine("x"))))
		assertTrue(ChapterPlanner.hasTimedSentences(lines))
	}

	@Test
	fun promptNumbersSentencesAndForbidsInventedTimes() {
		val prompt = ChapterPlanner.prompt(sentences)
		assertTrue(prompt.contains("[0] 開場與自我介紹"))
		assertTrue(prompt.contains("[2] 下一步"))
		assertTrue(prompt.contains("不要輸出或推算任何時間"))
		assertTrue(prompt.contains("3 到 ${ChapterPlanner.MAX_CHAPTERS}"))
	}

	@Test
	fun validAnswerIsMappedToRealTimes() {
		val answer = """[{"title":"開場","start_sentence_index":0},{"title":"報價","start_sentence_index":1}]"""
		val drafts = ChapterPlanner.parse(answer, sentences)
		assertEquals(listOf("開場", "報價"), drafts?.map { it.title })
		assertEquals(listOf(0L, 60_000L), drafts?.map { it.startMs })
		assertEquals(listOf(0, 1), drafts?.map { it.startSentenceIndex })
	}

	@Test
	fun answerWrappedInCodeFenceOrProseStillParses() {
		val answer = "好的，以下是章節：\n```json\n[{\"title\":\"開場\",\"start_sentence_index\":0}]\n```\n"
		assertEquals(1, ChapterPlanner.parse(answer, sentences)?.size)
	}

	@Test
	fun outOfRangeIndexRejectsTheWholeAnswer() {
		assertNull(ChapterPlanner.parse("""[{"title":"a","start_sentence_index":0},{"title":"b","start_sentence_index":9}]""", sentences))
	}

	@Test
	fun nonIncreasingOrDuplicateIndexesRejectTheWholeAnswer() {
		assertNull(ChapterPlanner.parse("""[{"title":"a","start_sentence_index":1},{"title":"b","start_sentence_index":1}]""", sentences))
		assertNull(ChapterPlanner.parse("""[{"title":"a","start_sentence_index":2},{"title":"b","start_sentence_index":1}]""", sentences))
	}

	@Test
	fun emptyOrBrokenAnswersAreRejected() {
		assertNull(ChapterPlanner.parse("[]", sentences))
		assertNull(ChapterPlanner.parse("", sentences))
		assertNull(ChapterPlanner.parse("沒有 JSON", sentences))
		assertNull(ChapterPlanner.parse("""[{"title":"","start_sentence_index":0}]""", sentences))
		assertNull(ChapterPlanner.parse("""[{"start_sentence_index":0}]""", sentences))
		assertNull(ChapterPlanner.parse("""[{"title":"a","start_sentence_index":"x"}]""", sentences))
		assertNull(ChapterPlanner.parse("""[{"title":"a","start_sentence_index":0}]""", emptyList()))
	}

	@Test
	fun titlesAreTruncatedAndTooManyChaptersRejected() {
		val long = "很長的標題".repeat(6)
		val drafts = ChapterPlanner.parse("""[{"title":"$long","start_sentence_index":0}]""", sentences)
		assertEquals(ChapterPlanner.MAX_TITLE_CHARS, drafts?.first()?.title?.length)

		val manySentences = (0..ChapterPlanner.MAX_CHAPTERS).map {
			com.eva.cloudtranscribe.chapters.ChapterSentence(it, it * 1_000L, "s$it")
		}
		val tooMany = manySentences.joinToString(",", "[", "]") { s ->
			"""{"title":"c${s.index}","start_sentence_index":${s.index}}"""
		}
		assertNull(ChapterPlanner.parse(tooMany, manySentences))
	}

	@Test
	fun revisionChangesWhenTheTranscriptChanges() {
		assertEquals("t1@100", ChapterPlanner.revisionOf("t1", 100))
		assertTrue(ChapterPlanner.revisionOf("t1", 100) != ChapterPlanner.revisionOf("t1", 101))
	}
}
