package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.model.PlaybackLine
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** On-device search inside one saved transcript: pure helpers, no network and no stored state. */
class TranscriptSearchTests {

	private val lines = listOf(
		PlaybackLine("下週三交 API 報價", 0, 1000),
		PlaybackLine("好", 1000, 2000),
		PlaybackLine("api latency 太高", 2000, 3000),
		PlaybackLine("API", 3000, 4000),
	)

	@Test
	fun caseInsensitiveTrimmedSubstringMatches() {
		assertEquals(listOf(0, 2, 3), PlaybackTranscripts.search(lines, "  API "))
		assertEquals(listOf(0), PlaybackTranscripts.search(lines, "報價"))
		assertEquals(listOf(1), PlaybackTranscripts.search(lines, "好"))
		assertEquals(listOf(2), PlaybackTranscripts.search(lines, "Latency"))
	}

	@Test
	fun blankAndMissingQueriesReturnNoMatches() {
		assertTrue(PlaybackTranscripts.search(lines, "").isEmpty())
		assertTrue(PlaybackTranscripts.search(lines, "   ").isEmpty())
		assertTrue(PlaybackTranscripts.search(lines, "\n\t").isEmpty())
		assertTrue(PlaybackTranscripts.search(lines, "不存在").isEmpty())
		assertTrue(PlaybackTranscripts.search(emptyList(), "api").isEmpty())
	}

	@Test
	fun queryIsLiteralTextNotPattern() {
		val literal = listOf(PlaybackLine("(測試) 1+1=2"), PlaybackLine("a.c"))
		assertEquals(listOf(0), PlaybackTranscripts.search(literal, "(測試)"))
		assertEquals(listOf(0), PlaybackTranscripts.search(literal, "1+1"))
		assertEquals(listOf(1), PlaybackTranscripts.search(literal, "."))
		assertTrue(PlaybackTranscripts.search(literal, ".*").isEmpty())
	}

	@Test
	fun repeatedOccurrenceOnOneLineCountsOnce() {
		val repeated = listOf(PlaybackLine("api 和 API 都要改"))
		assertEquals(listOf(0), PlaybackTranscripts.search(repeated, "api"))
	}

	@Test
	fun phraseSplitAcrossLinesIsNotAMatch() {
		val split = listOf(PlaybackLine("下週三交"), PlaybackLine("API 報價"))
		assertTrue(PlaybackTranscripts.search(split, "交 API").isEmpty())
		assertEquals(listOf(0), PlaybackTranscripts.search(split, "交"))
		assertEquals(listOf(1), PlaybackTranscripts.search(split, "報價"))
	}

	@Test
	fun searchKeepsListOrderAndHandlesUntimedLines() {
		val untimed = listOf(PlaybackLine("一 api"), PlaybackLine("二"), PlaybackLine("三 api"))
		assertEquals(listOf(0, 2), PlaybackTranscripts.search(untimed, "api"))
		val record = TranscriptRecord(
			id = "r",
			recordingId = 1,
			source = TranscriptSource.OFFLINE,
			provenance = Provenance(engine = "e", model = "m"),
			createdAtMillis = 1,
			status = RunStatus.SUCCESS,
			text = "一 api\n\n二\n三 api",
		)
		assertEquals(listOf(0, 2), PlaybackTranscripts.search(PlaybackTranscripts.lines(record), "api"))
	}

	@Test
	fun stepWrapsBothWaysAndStartsAtTheNearEnd() {
		val m = listOf(0, 2)
		assertEquals(0, PlaybackTranscripts.stepMatch(m, -1, forward = true))
		assertEquals(2, PlaybackTranscripts.stepMatch(m, -1, forward = false))
		assertEquals(2, PlaybackTranscripts.stepMatch(m, 0, forward = true))
		assertEquals(0, PlaybackTranscripts.stepMatch(m, 2, forward = true))
		assertEquals(2, PlaybackTranscripts.stepMatch(m, 0, forward = false))
		assertEquals(0, PlaybackTranscripts.stepMatch(m, 2, forward = false))
		assertEquals(-1, PlaybackTranscripts.stepMatch(emptyList(), 0, forward = true))
		assertEquals(-1, PlaybackTranscripts.stepMatch(emptyList(), 0, forward = false))
	}

	@Test
	fun singleMatchStepsBackToItself() {
		val one = listOf(3)
		assertEquals(3, PlaybackTranscripts.stepMatch(one, 3, forward = true))
		assertEquals(3, PlaybackTranscripts.stepMatch(one, 3, forward = false))
	}

	@Test
	fun stepFromLineThatIsNoLongerAMatchJumpsToFirstOrLast() {
		val m = listOf(1, 4, 7)
		assertEquals(1, PlaybackTranscripts.stepMatch(m, 5, forward = true))
		assertEquals(7, PlaybackTranscripts.stepMatch(m, 5, forward = false))
	}

	@Test
	fun counterIsOneBasedAndZeroOffAMatch() {
		val m = listOf(0, 2, 3)
		assertEquals(1, PlaybackTranscripts.matchPosition(m, 0))
		assertEquals(2, PlaybackTranscripts.matchPosition(m, 2))
		assertEquals(3, PlaybackTranscripts.matchPosition(m, 3))
		assertEquals(0, PlaybackTranscripts.matchPosition(m, -1))
		assertEquals(0, PlaybackTranscripts.matchPosition(m, 1))
		assertEquals(0, PlaybackTranscripts.matchPosition(emptyList(), 0))
	}
}
