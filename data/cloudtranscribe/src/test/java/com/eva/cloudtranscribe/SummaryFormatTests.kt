package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.engine.Prompts
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.ProviderType
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryFormat
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.cloudtranscribe.work.newSummaryRecord
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Summary formats change only the instructions: default stays byte-identical, and no format invents content. */
class SummaryFormatPromptTests {

	private val transcript = "Speaker 1: 下週三前完成報價單。"
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

	/** Exact pre-format prompt; any change here is a regression, not an improvement. */
	private fun legacy(text: String) = "請用繁體中文精簡整理以下會議逐字稿的重點。只寫明確說出的重要事實、決議與待辦；" +
		"沒有的類別就省略，不必湊數。每點一句、以「- 」開頭，技術名詞與英文原樣保留。不要開場白、結語或客套話。" +
		"不要推測或捏造內容、負責人或期限；重複字、語助詞與雜訊不是重點。完全沒有重點時只輸出：目前無重點\n\n逐字稿：\n$text"

	@Test
	fun defaultFormatIsByteIdenticalToTheOldPrompt() {
		assertEquals(legacy(transcript), Prompts.summary(transcript))
		assertEquals(legacy(transcript), Prompts.summary(transcript, SummaryFormat.CONCISE))
		assertEquals(legacy(transcript), Prompts.summary(transcript, SummaryFormat.CONCISE, "被忽略的自訂文字"))
	}

	@Test
	fun everyFormatKeepsTheOmissionRuleAndTheNoPointsAnswer() {
		SummaryFormat.entries.forEach { format ->
			val prompt = Prompts.summary(transcript, format, customInstructions = "分成三段")
			assertTrue("$format must allow omission", prompt.contains("省略"))
			assertTrue("$format must answer 目前無重點", prompt.contains("目前無重點"))
			assertTrue("$format must not invite invented owners", prompt.contains("不要推測或捏造"))
			assertTrue("$format must keep the transcript", prompt.contains(transcript))
		}
	}

	@Test
	fun formatsDifferFromTheConciseDefaultAndFromEachOther() {
		val concise = Prompts.summary(transcript)
		val decisions = Prompts.summary(transcript, SummaryFormat.DECISIONS_AND_ACTIONS)
		val questions = Prompts.summary(transcript, SummaryFormat.BULLETS_AND_OPEN_QUESTIONS)
		assertNotEquals(concise, decisions)
		assertNotEquals(concise, questions)
		assertNotEquals(decisions, questions)
		assertTrue(decisions.contains("決議"))
		assertTrue(decisions.contains("待辦"))
		assertTrue(questions.contains("未解"))
	}

	@Test
	fun customFormatFallsBackWhenBlankAndIsBoundedWhenLong() {
		val blank = Prompts.summary(transcript, SummaryFormat.CUSTOM, "   ")
		assertEquals(Prompts.summary(transcript), blank)

		val long = "x".repeat(CloudSettings.MAX_SUMMARY_INSTRUCTIONS + 800)
		val prompt = Prompts.summary(transcript, SummaryFormat.CUSTOM, long)
		val inserted = Regex("x+").find(prompt)?.value
		assertEquals(CloudSettings.MAX_SUMMARY_INSTRUCTIONS, inserted?.length)
		assertTrue(prompt.contains("目前無重點"))
	}

	@Test
	fun storedSettingsFromBeforeFormatsStillLoadAsConcise() {
		// exactly what an older build stored: no format fields at all
		val legacyJson = """{"providerType":"OPENAI_COMPATIBLE","baseUrl":"https://x","transcribeModel":"m","summaryModel":"m"}"""
		val settings = json.decodeFromString(CloudSettings.serializer(), legacyJson)
		assertEquals(SummaryFormat.CONCISE, settings.summaryFormat)
		assertEquals("", settings.customSummaryInstructions)
		assertEquals(SummaryFormat.CONCISE, settings.effectiveSummaryFormat)
	}

	@Test
	fun customWithoutTextDegradesToConciseButKeepsTheChoiceForLater() {
		val settings = CloudSettings(summaryFormat = SummaryFormat.CUSTOM, customSummaryInstructions = "   ")
		assertEquals(SummaryFormat.CONCISE, settings.effectiveSummaryFormat)
		assertEquals(SummaryFormat.CUSTOM, settings.summaryFormat)

		val filled = settings.copy(customSummaryInstructions = "先決議、再待辦")
		assertEquals(SummaryFormat.CUSTOM, filled.effectiveSummaryFormat)
	}

	@Test
	fun settingsRoundTripKeepsTheFormat() {
		val saved = CloudSettings(
			providerType = ProviderType.OPENAI_COMPATIBLE,
			baseUrl = "https://x",
			summaryFormat = SummaryFormat.DECISIONS_AND_ACTIONS,
		)
		val decoded = json.decodeFromString(CloudSettings.serializer(), json.encodeToString(CloudSettings.serializer(), saved))
		assertEquals(SummaryFormat.DECISIONS_AND_ACTIONS, decoded.summaryFormat)
		assertEquals("", decoded.customSummaryInstructions)
	}
}

/** A re-run must always be a new record so the previous summary survives untouched. */
class SummaryFormatRecordTests {

	private val settings = CloudSettings(
		providerType = ProviderType.OPENAI_COMPATIBLE,
		baseUrl = "https://api.example.test/v1",
		summaryModel = "m",
		summaryFormat = SummaryFormat.CONCISE,
	)

	private fun oldSummary(transcriptId: String) = SummaryRecord(
		id = "summary-old",
		recordingId = 7,
		sourceTranscriptId = transcriptId,
		provenance = Provenance(engine = "cloud-summary", model = "m"),
		createdAtMillis = 1_000,
		status = RunStatus.SUCCESS,
		text = "- 舊摘要",
	)

	@Test
	fun newSummaryRecordIsQueuedForTheChosenFormatAndSameTranscript() {
		val record = newSummaryRecord(
			id = "summary-new",
			recordingId = 7,
			transcriptId = "t-1",
			settings = settings,
			format = SummaryFormat.BULLETS_AND_OPEN_QUESTIONS,
			now = 5_000,
		)
		assertEquals("summary-new", record.id)
		assertEquals(7L, record.recordingId)
		assertEquals("t-1", record.sourceTranscriptId)
		assertEquals(RunStatus.QUEUED, record.status)
		assertEquals(5_000L, record.createdAtMillis)
		assertEquals(SummaryFormat.BULLETS_AND_OPEN_QUESTIONS.name, record.provenance.summaryFormat)
		assertEquals("api.example.test", record.provenance.endpointHost)
		assertEquals("m", record.provenance.model)
	}

	@Test
	fun savingTheNewSummaryLeavesTheOldOneUntouched() {
		val store = TranscriptStore(Files.createTempDirectory("summary-format").toFile())
		val old = oldSummary("t-1")
		store.saveSummary(old)

		val new = newSummaryRecord(
			id = "summary-new",
			recordingId = 7,
			transcriptId = "t-1",
			settings = settings,
			format = SummaryFormat.DECISIONS_AND_ACTIONS,
			now = 5_000,
		).copy(status = RunStatus.SUCCESS, text = "- 新摘要")
		store.saveSummary(new)

		val all = store.summaries(7)
		assertEquals(setOf("summary-old", "summary-new"), all.map { it.id }.toSet())
		val reloadedOld = store.summary(7, "summary-old")
		assertEquals("- 舊摘要", reloadedOld?.text)
		assertEquals(RunStatus.SUCCESS, reloadedOld?.status)
		assertNull(reloadedOld?.provenance?.summaryFormat)
		assertEquals(SummaryFormat.DECISIONS_AND_ACTIONS.name, store.summary(7, "summary-new")?.provenance?.summaryFormat)
	}
}
