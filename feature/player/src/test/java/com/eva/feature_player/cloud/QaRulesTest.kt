package com.eva.feature_player.cloud

import com.eva.cloudtranscribe.model.PlaybackLine
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.qa.RecordingQa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QaRulesTest {

	private val transcript = TranscriptRecord("t", 1, TranscriptSource.OFFLINE, Provenance("offline", model = "m"), 0, status = RunStatus.SUCCESS, text = "x")

	@Test
	fun asking_is_blocked_without_usable_transcript() {
		assertTrue(QaRules.blockedReason(null, null)!!.contains("還沒有"))
		assertTrue(QaRules.blockedReason(transcript, RecordingQa.context(emptyList()))!!.contains("空的"))
		assertNull(QaRules.blockedReason(transcript, RecordingQa.context(RecordingQa.segments(listOf(PlaybackLine("a"))))))
	}

	@Test
	fun record_keeps_answer_citations_and_coverage() {
		val ctx = RecordingQa.context(RecordingQa.segments(listOf(PlaybackLine("報價週三交", 1_000, 2_000), PlaybackLine("b".repeat(80), 2_000, 3_000))), maxChars = 40)
		val record = QaRules.record(1, "t", "何時交？", "週三交報價〔S1〕", ctx, 10, "")
		assertEquals(listOf(1), record.citations.map { it.segmentId })
		assertEquals(1_000L, record.citations.single().startMs)
		assertTrue(record.coverageNote!!.startsWith("只用了前"))
		assertNull(record.model)
		assertTrue(record.id.startsWith("qa-10-"))
	}
}
