package io.github.richardhan.voicenote

import org.junit.Assert.*
import org.junit.Test

class MeetingNotesTest {
	@Test fun bilingualRequestsPreserveOrderAndDoNotSplitIndividualPassages() {
		val passages = List(6) { MeetingPassage("段".repeat(1500), it * 1000L) }
		val batches = meetingBatches(MeetingTool.Translation, passages)
		assertEquals(listOf(2, 2, 2), batches.map { it.size })
		assertEquals(passages, batches.flatten())
		assertEquals(listOf(passages), meetingBatches(MeetingTool.Decisions, passages))
	}

	@Test fun offlineKeywordsAndFillersMatchWordsInBothLanguages() {
		val text = "Release release, the the. 模型測試模型測試。嗯，嗯，like unlikely."
		assertTrue("release" in transcriptKeywords(text))
		assertTrue("模型測試" in transcriptKeywords(text))
		assertFalse("the" in transcriptKeywords(text))
		assertEquals(mapOf("嗯" to 2, "like" to 1), transcriptFillers(text).toMap())
	}
}
