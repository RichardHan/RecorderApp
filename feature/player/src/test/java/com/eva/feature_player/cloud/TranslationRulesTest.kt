package com.eva.feature_player.cloud

import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.translate.SavedTranslation
import com.eva.cloudtranscribe.translate.TranslationLanguage
import com.eva.cloudtranscribe.translate.TranslationSentence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRulesTest {

	private fun record(status: RunStatus = RunStatus.SUCCESS, text: String = "你好\n再見") =
		TranscriptRecord("t", 1, TranscriptSource.OFFLINE, Provenance("offline", model = "m"), 0, status = status, text = text)

	private val chunks = listOf(listOf(TranslationSentence(1, "你好")), listOf(TranslationSentence(2, "再見")))

	@Test
	fun blocked_for_missing_running_or_empty_transcripts() {
		assertTrue(TranslationRules.blockedReason(null) != null)
		assertTrue(TranslationRules.blockedReason(record(RunStatus.RUNNING))!!.contains("轉錄中"))
		assertTrue(TranslationRules.blockedReason(record(text = " "))!!.contains("空"))
		assertNull(TranslationRules.blockedReason(record()))
	}

	@Test
	fun all_chunks_succeed_in_order() = runTest {
		val answers = ArrayDeque(listOf("[{\"id\":1,\"translation\":\"Hello\"}]", "[{\"id\":2,\"translation\":\"Bye\"}]"))
		val result = TranslationRules.translateAll(chunks, TranslationLanguage.EN) { CallOutcome.Text(answers.removeFirst()) }
		assertEquals(listOf("Hello", "Bye"), (result as TranslationRules.Result.Done).lines.map { it.translation })
	}

	@Test
	fun one_bad_chunk_fails_everything_and_stops_sending() = runTest {
		var calls = 0
		val result = TranslationRules.translateAll(chunks + chunks, TranslationLanguage.JA) {
			calls++
			if (calls == 2) CallOutcome.Text("[]") else CallOutcome.Text("[{\"id\":1,\"translation\":\"こんにちは\"}]")
		}
		assertTrue(result is TranslationRules.Result.Failed)
		assertTrue((result as TranslationRules.Result.Failed).reason.contains("第 2/4 段"))
		assertEquals(2, calls)
	}

	@Test
	fun only_translation_of_the_same_transcript_is_used() {
		val a = SavedTranslation(1, "t", "en", "t@0", emptyList(), 1)
		val b = a.copy(transcriptId = "other", targetLang = "ja")
		assertEquals(a, TranslationRules.forRecord(listOf(b, a), record(), null))
		assertNull(TranslationRules.forRecord(listOf(b), record(), null))
		assertNull(TranslationRules.forRecord(listOf(a), record(), "ja"))
	}
}
