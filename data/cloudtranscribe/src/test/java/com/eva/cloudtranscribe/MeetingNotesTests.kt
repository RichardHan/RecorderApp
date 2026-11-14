package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.live.LiveCloudSession
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.live.SummaryProbe
import com.eva.cloudtranscribe.live.TranscriptMerge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Optional pre-meeting notes / agenda: they may only sharpen the live key points, must never change the prompt when
 * empty, must stay bounded, and must survive storage written by older builds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeetingNotesTests {

	private val notes = "議題：Q3 報價；與會：Amy"
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

	private fun prompt(meetingNotes: String) =
		TranscriptMerge.summaryPrompt("自訂指令", "舊重點", "新雲端稿", "", meetingNotes = meetingNotes)

	private fun pcm(seconds: Int, rate: Int = 100) = ShortArray(seconds * rate)

	@Test
	fun blankNotesLeaveThePromptUntouched() {
		val base = prompt("")
		assertEquals(base, prompt("   "))
		assertEquals(base, prompt("\n\t "))
		assertFalse(base.contains("會前備註"))
	}

	@Test
	fun notesSitAfterTheUserPromptAndBeforeTheKeyPoints() {
		val p = prompt(notes)
		assertTrue(p.startsWith("自訂指令"))
		assertTrue(p.contains(notes))
		assertTrue(p.contains("不代表會中說過"))
		assertTrue(p.indexOf(notes) < p.indexOf("【目前重點】"))
		assertTrue(p.indexOf("【目前重點】") < p.indexOf("【新增逐字稿"))
	}

	@Test
	fun notesAreTrimmedAndCappedAtTheLimit() {
		val long = "x".repeat(LiveCloudSettings.MAX_MEETING_NOTES + 500)
		val inserted = Regex("x+").find(prompt(long))?.value
		assertEquals(LiveCloudSettings.MAX_MEETING_NOTES, inserted?.length)
		assertEquals(LiveCloudSettings.MAX_MEETING_NOTES, LiveCloudSettings(meetingNotes = long).normalized().meetingNotes.length)
		assertEquals(notes, prompt("  $notes  ").substringAfter("】\n").substringBefore("\n\n"))
	}

	@Test
	fun savedSettingsTrimTheEdgesButKeepInnerLineBreaks() {
		val s = LiveCloudSettings(meetingNotes = "  第一行\n第二行  ").normalized()
		assertEquals("第一行\n第二行", s.meetingNotes)
		assertEquals("", LiveCloudSettings().meetingNotes)
	}

	@Test
	fun summaryProbeSendsSavedNotesOnlyWhenPresent() {
		val withNotes = LiveCloudSettings(enabled = true, consentAcceptedAtMillis = 1L, meetingNotes = notes).normalized()
		assertTrue(SummaryProbe.buildPrompt(withNotes).contains(notes))
		val empty = LiveCloudSettings(enabled = true, consentAcceptedAtMillis = 1L, meetingNotes = "   ").normalized()
		assertFalse(SummaryProbe.buildPrompt(empty).contains("會前備註"))
	}

	@Test
	fun liveSessionPutsNotesIntoTheSummaryRequest() = runTest {
		val prompts = mutableListOf<String>()
		val s = LiveCloudSession(backgroundScope, LiveCloudSettings(enabled = true, consentAcceptedAtMillis = 1L, meetingNotes = notes), "notes",
			transcribe = { _, _, _ -> CallOutcome.Text("雲端稿") },
			summarize = { p -> prompts += p; CallOutcome.Text("- 重點") },
			sampleRate = 100)
		repeat(90) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertEquals(1, prompts.size)
		assertTrue(prompts[0].contains(notes))
		assertTrue(prompts[0].contains("雲端稿"))
		assertTrue(prompts[0].indexOf("會前備註") < prompts[0].indexOf("【目前重點】"))
		s.cancel()
	}

	@Test
	fun liveSessionWithoutNotesSendsTheOriginalPrompt() = runTest {
		val prompts = mutableListOf<String>()
		val s = LiveCloudSession(backgroundScope, LiveCloudSettings(enabled = true, consentAcceptedAtMillis = 1L), "no-notes",
			transcribe = { _, _, _ -> CallOutcome.Text("雲端稿") },
			summarize = { p -> prompts += p; CallOutcome.Text("- 重點") },
			sampleRate = 100)
		repeat(90) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertEquals(1, prompts.size)
		assertFalse(prompts[0].contains("會前備註"))
		s.cancel()
	}

	@Test
	fun perRecordingNotesReplaceTheGlobalNotesInTheLiveRequest() = runTest {
		val prompts = mutableListOf<String>()
		val s = LiveCloudSession(backgroundScope, LiveCloudSettings(enabled = true, consentAcceptedAtMillis = 1L, meetingNotes = notes), "per-rec",
			transcribe = { _, _, _ -> CallOutcome.Text("雲端稿") },
			summarize = { p -> prompts += p; CallOutcome.Text("- 重點") },
			sampleRate = 100,
			meetingNotes = { com.eva.cloudtranscribe.notes.RecordingNotes.effective("本場：預算", notes) })
		repeat(90) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertEquals(1, prompts.size)
		assertTrue(prompts[0].contains("本場：預算"))
		assertFalse(prompts[0].contains(notes))
		s.cancel()
	}

	@Test
	fun storedJsonFromAnOlderBuildStillLoadsWithoutNotes() {
		val legacy = """{"enabled":true,"consentAcceptedAtMillis":1,"summaryModel":"m","summaryPrompt":"p"}"""
		val s = json.decodeFromString(LiveCloudSettings.serializer(), legacy)
		assertEquals("", s.meetingNotes)
		assertEquals("m", s.summaryModel)
	}

	@Test
	fun notesSurviveAStoreRoundTrip() {
		val saved = LiveCloudSettings(meetingNotes = "  $notes  ").normalized()
		val encoded = json.encodeToString(LiveCloudSettings.serializer(), saved)
		val decoded = json.decodeFromString(LiveCloudSettings.serializer(), encoded)
		assertEquals(notes, decoded.meetingNotes)
		assertEquals(saved, decoded)
	}
}
