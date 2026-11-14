package com.eva.feature_player.cloud

import com.eva.cloudtranscribe.decisions.DecisionDraft
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.recordings.domain.models.RecordingDecisionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionsRulesTest {

	private fun transcript(status: RunStatus = RunStatus.SUCCESS, text: String = "我們決定用 A") =
		TranscriptRecord("t", 1, TranscriptSource.OFFLINE, Provenance("offline", model = "m"), 0, status = status, text = text)

	@Test
	fun extraction_is_blocked_without_a_finished_transcript() {
		assertTrue(DecisionsRules.blockedReason(null)!!.contains("還沒有"))
		assertTrue(DecisionsRules.blockedReason(transcript(RunStatus.RUNNING))!!.contains("轉錄中"))
		assertTrue(DecisionsRules.blockedReason(transcript(text = " "))!!.contains("沒有可用"))
		assertNull(DecisionsRules.blockedReason(transcript()))
		assertNull(DecisionsRules.blockedReason(transcript(RunStatus.PARTIAL)))
	}

	@Test
	fun drafts_become_extracted_models_with_revision() {
		val models = DecisionsRules.toModels(5, listOf(DecisionDraft("採用 A", 2, 5_000), DecisionDraft("延後", null, null)), "t@1", 99)
		assertEquals(listOf(5_000L, null), models.map { it.startMs })
		assertTrue(models.all { it.isExtracted && it.transcriptRevision == "t@1" && it.recordingId == 5L && it.id == 0L })
	}

	@Test
	fun only_extracted_items_can_be_stale() {
		val manual = RecordingDecisionModel(1, 5, "手動", createdAtMillis = 0)
		val extracted = manual.copy(id = 2, source = RecordingDecisionModel.SOURCE_EXTRACTED, transcriptRevision = "t@1")
		assertFalse(DecisionsRules.isStale(listOf(manual), "t@2"))
		assertFalse(DecisionsRules.isStale(listOf(manual, extracted), "t@1"))
		assertTrue(DecisionsRules.isStale(listOf(manual, extracted), "t@2"))
	}
}
