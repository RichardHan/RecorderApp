package com.eva.cloudtranscribe.model

import com.eva.cloudtranscribe.api.HttpResponse
import com.eva.cloudtranscribe.api.HttpTransport
import com.eva.cloudtranscribe.api.OpenAiCompatibleClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortPolicyTest {

	private val zh = ReasoningEffortPolicy.Copy(
		appliesToSummary = "套用於摘要模型 %1\$s",
		appliesToSummarySkipped = "套用於摘要模型 %1\$s 不送（%2\$s）",
		transcribeSent = "轉錄模型 %1\$s 會送",
		transcribeSkipped = "轉錄模型 %1\$s 不送（%2\$s）",
		join = "%1\$s；%2\$s",
		skipOff = "推理強度為關",
		skipDefaultModel = "預設轉錄模型",
		skipDiarization = "講者辨識已開",
		skipTranscribeName = "名稱含 transcribe",
		skipFlashLite = "名稱含 flash-lite",
		skipGeminiNative = "Gemini 原生介面不送",
	)

	@Test
	fun defaultSettingsSendSummaryAndSkipDefaultTranscribeModel() {
		val settings = CloudSettings()
		val note = ReasoningEffortPolicy.of(settings)
		assertEquals(CloudSettings.FLASH_MODEL, note.summaryModel)
		assertEquals(CloudSettings.DEFAULT_MODEL, note.transcribeModel)
		assertTrue(note.summarySent)
		assertFalse(note.transcribeSent)
		assertEquals(ReasoningEffortSkip.DEFAULT_TRANSCRIBE_MODEL, note.transcribeSkip)
	}

	@Test
	fun transcribeNameIsSkippedAndSummaryIsSent() {
		val settings = CloudSettings(transcribeModel = CloudSettings.SPEAKER_MODEL)
		val note = ReasoningEffortPolicy.of(settings)
		assertTrue(note.summarySent)
		assertEquals(ReasoningEffortSkip.MODEL_NAME_TRANSCRIBE, note.transcribeSkip)
		assertEquals(
			"套用於摘要模型 gemini-3.8-flash；轉錄模型 gemini-3.5-transcribe 不送（名稱含 transcribe）",
			ReasoningEffortPolicy.formatOneLiner(note, zh),
		)
	}

	@Test
	fun bothModelsReceiveEffortWhenEligible() {
		val settings = CloudSettings(transcribeModel = CloudSettings.FLASH_MODEL)
		val note = ReasoningEffortPolicy.of(settings)
		assertTrue(note.summarySent)
		assertTrue(note.transcribeSent)
		assertEquals(
			"套用於摘要模型 gemini-3.8-flash；轉錄模型 gemini-3.8-flash 會送",
			ReasoningEffortPolicy.formatOneLiner(note, zh),
		)
	}

	@Test
	fun effortOffSkipsBothBeforeMoreSpecificTranscribeReasons() {
		val settings = CloudSettings(
			transcribeModel = CloudSettings.SPEAKER_MODEL,
			reasoningEffort = ReasoningEffort.OFF,
			speakerDiarization = true,
		)
		val note = ReasoningEffortPolicy.of(settings)
		assertEquals(ReasoningEffortSkip.OFF, note.summarySkip)
		assertEquals(ReasoningEffortSkip.OFF, note.transcribeSkip)
		assertEquals(
			"套用於摘要模型 gemini-3.8-flash 不送（推理強度為關）；轉錄模型 gemini-3.5-transcribe 不送（推理強度為關）",
			ReasoningEffortPolicy.formatOneLiner(note, zh),
		)
	}

	@Test
	fun speakerDiarizationSkipsTranscriptionOnly() {
		val settings = CloudSettings(transcribeModel = CloudSettings.FLASH_MODEL, speakerDiarization = true)
		val note = ReasoningEffortPolicy.of(settings)
		assertTrue(note.summarySent)
		assertEquals(ReasoningEffortSkip.SPEAKER_DIARIZATION, note.transcribeSkip)
		assertEquals(
			"套用於摘要模型 gemini-3.8-flash；轉錄模型 gemini-3.8-flash 不送（講者辨識已開）",
			ReasoningEffortPolicy.formatOneLiner(note, zh),
		)
	}

	@Test
	fun flashLiteNameIsDetectedCaseInsensitively() {
		val settings = CloudSettings(
			transcribeModel = "Gemini-2.0-Flash-Lite",
			summaryModel = "gemini-2.0-flash-lite-preview",
		)
		val note = ReasoningEffortPolicy.of(settings)
		assertEquals(ReasoningEffortSkip.MODEL_NAME_FLASH_LITE, note.summarySkip)
		assertEquals(ReasoningEffortSkip.MODEL_NAME_FLASH_LITE, note.transcribeSkip)
		assertEquals(
			"套用於摘要模型 gemini-2.0-flash-lite-preview 不送（名稱含 flash-lite）；轉錄模型 Gemini-2.0-Flash-Lite 不送（名稱含 flash-lite）",
			ReasoningEffortPolicy.formatOneLiner(note, zh),
		)
	}

	@Test
	fun summaryTranscribeNameDoesNotBlockEligibleTranscriptionModel() {
		val settings = CloudSettings(
			transcribeModel = CloudSettings.FLASH_MODEL,
			summaryModel = "gpt-4o-transcribe",
		)
		val note = ReasoningEffortPolicy.of(settings)
		assertEquals(ReasoningEffortSkip.MODEL_NAME_TRANSCRIBE, note.summarySkip)
		assertTrue(note.transcribeSent)
		assertEquals(
			"套用於摘要模型 gpt-4o-transcribe 不送（名稱含 transcribe）；轉錄模型 gemini-3.8-flash 會送",
			ReasoningEffortPolicy.formatOneLiner(note, zh),
		)
	}

	@Test
	fun geminiNativeNeverSendsEvenWhenModelsWouldQualify() {
		val settings = CloudSettings(
			providerType = ProviderType.GEMINI_NATIVE,
			transcribeModel = CloudSettings.FLASH_MODEL,
			summaryModel = CloudSettings.FLASH_MODEL,
		)
		val note = ReasoningEffortPolicy.of(settings)
		assertEquals(ReasoningEffortSkip.GEMINI_NATIVE, note.summarySkip)
		assertEquals(ReasoningEffortSkip.GEMINI_NATIVE, note.transcribeSkip)
		assertEquals(
			"套用於摘要模型 gemini-3.8-flash 不送（Gemini 原生介面不送）；轉錄模型 gemini-3.8-flash 不送（Gemini 原生介面不送）",
			ReasoningEffortPolicy.formatOneLiner(note, zh),
		)
	}

	@Test
	fun shouldSendMatchesOpenAiCompatibleClient() {
		val cases = listOf(
			"gemini-3.8-flash" to ReasoningEffort.LOW,
			"gemini-3.5-transcribe" to ReasoningEffort.LOW,
			"GEMINI-3.5-TRANSCRIBE" to ReasoningEffort.MEDIUM,
			"gemini-2.0-flash-lite" to ReasoningEffort.HIGH,
			"gemini-3.8-flash" to ReasoningEffort.OFF,
			CloudSettings.DEFAULT_MODEL to ReasoningEffort.LOW,
		)
		cases.forEach { (model, effort) ->
			val client = OpenAiCompatibleClient("https://example.test/v1", "k", model, effort, UnusedTransport)
			assertEquals(
				"model=$model effort=$effort",
				ReasoningEffortPolicy.shouldSend(model, effort),
				client.shouldSendEffort(),
			)
		}
	}

	@Test
	fun transcriptionEffortOffIsEquivalentToPolicySkipForDefaultModelAndDiarization() {
		val defaultModel = CloudSettings(transcribeModel = CloudSettings.DEFAULT_MODEL)
		assertEquals(ReasoningEffort.OFF, defaultModel.transcriptionEffort)
		assertFalse(ReasoningEffortPolicy.shouldSend(defaultModel.transcribeModel, defaultModel.transcriptionEffort))
		assertEquals(ReasoningEffortSkip.DEFAULT_TRANSCRIBE_MODEL, ReasoningEffortPolicy.of(defaultModel).transcribeSkip)

		val diarized = CloudSettings(transcribeModel = CloudSettings.FLASH_MODEL, speakerDiarization = true)
		assertEquals(ReasoningEffort.OFF, diarized.transcriptionEffort)
		assertFalse(ReasoningEffortPolicy.shouldSend(diarized.transcribeModel, diarized.transcriptionEffort))
		assertEquals(ReasoningEffortSkip.SPEAKER_DIARIZATION, ReasoningEffortPolicy.of(diarized).transcribeSkip)

		val eligible = CloudSettings(transcribeModel = CloudSettings.FLASH_MODEL)
		assertEquals(ReasoningEffort.LOW, eligible.transcriptionEffort)
		assertTrue(ReasoningEffortPolicy.shouldSend(eligible.transcribeModel, eligible.transcriptionEffort))
		assertNull(ReasoningEffortPolicy.of(eligible).transcribeSkip)
	}

	private object UnusedTransport : HttpTransport {
		override fun postJson(url: String, headers: Map<String, String>, body: String, timeoutSeconds: Int): HttpResponse =
			error("unused")
	}
}
