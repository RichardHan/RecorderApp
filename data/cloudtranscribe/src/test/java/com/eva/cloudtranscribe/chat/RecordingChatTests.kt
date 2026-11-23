package com.eva.cloudtranscribe.chat

import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.ChatMessage
import com.eva.cloudtranscribe.api.ChatRole
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.HttpResponse
import com.eva.cloudtranscribe.api.HttpTransport
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.ProviderType
import com.eva.cloudtranscribe.model.ReasoningEffort
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SegmentStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.cloudtranscribe.usage.ModelPrice
import com.eva.cloudtranscribe.usage.TokenUsageHooks
import com.eva.cloudtranscribe.usage.TokenUsageStore
import com.eva.cloudtranscribe.usage.boundTo
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingChatTests {

	@After
	fun teardown() {
		TokenUsageHooks.resetForTests()
	}

	private fun transcript(
		id: String = "t1",
		recordingId: Long = 42L,
		segments: List<SegmentResult> = listOf(
			SegmentResult(0, 0, 5_000, SegmentStatus.OK, "大家好，今天討論報價"),
			SegmentResult(1, 5_000, 12_000, SegmentStatus.OK, "Amy 說報價下週三前交給客戶"),
			SegmentResult(2, 12_000, 18_000, SegmentStatus.OK, "預算上限是五十萬"),
		),
		text: String = "",
	) = TranscriptRecord(
		id = id,
		recordingId = recordingId,
		source = TranscriptSource.CLOUD,
		provenance = Provenance("cloud", model = "gemini-3.5-transcribe"),
		createdAtMillis = 1_000L,
		status = RunStatus.SUCCESS,
		text = text,
		segments = segments,
	)

	private fun summary(text: String = "- Amy 負責下週三前提交報價\n- 預算上限五十萬") = SummaryRecord(
		id = "s1",
		recordingId = 42L,
		sourceTranscriptId = "t1",
		provenance = Provenance("cloud-summary", model = "gemini-3.8-flash"),
		createdAtMillis = 2_000L,
		status = RunStatus.SUCCESS,
		text = text,
	)

	@Test
	fun system_prompt_includes_grounding_rules_summary_and_numbered_transcript() {
		val ctx = RecordingChat.buildContext(transcript(), summary())
		assertTrue(ctx.hasSummary)
		assertFalse(ctx.truncated)
		assertNull(ctx.coverageNote)

		val sys = RecordingChat.systemPrompt(ctx)
		assertTrue(sys.contains("只能根據下方提供的"))
		assertTrue(sys.contains("絕對不要捏造時間戳記或說話者"))
		assertTrue(sys.contains(RecordingChat.NOT_FOUND))
		assertTrue(sys.contains("【錄音摘要／重點】"))
		assertTrue(sys.contains("Amy 負責下週三前提交報價"))
		assertTrue(sys.contains("[S2 00:05–00:12] Amy 說報價下週三前交給客戶"))
	}

	@Test
	fun no_key_points_summary_is_omitted_and_pii_redaction_applies_when_enabled() {
		val t = transcript(
			segments = listOf(
				SegmentResult(0, 0, 4_000, SegmentStatus.OK, "請寄給 amy@example.com 或打 0912-345-678"),
			),
		)
		val ctx = RecordingChat.buildContext(t, summary("目前無重點"), redactPii = true)
		assertFalse(ctx.hasSummary)
		assertNull(ctx.summaryText)
		assertFalse(ctx.segments.single().text.contains("amy@example.com"))
		assertFalse(ctx.segments.single().text.contains("0912-345-678"))
	}

	@Test
	fun truncation_reports_coverage_note_for_long_transcript_and_long_summary() {
		val ctx = RecordingChat.buildContext(
			transcript = transcript(),
			summary = summary("重點".repeat(50)),
			maxTranscriptChars = 70,
			maxSummaryChars = 20,
		)
		assertTrue(ctx.truncated)
		assertTrue(ctx.transcriptTruncated)
		assertTrue(ctx.summaryTruncated)
		assertEquals(2, ctx.segments.size)
		val note = ctx.coverageNote!!
		assertTrue(note.contains("00:12"))
		assertTrue(note.contains("摘要已截斷"))
		assertTrue(RecordingChat.systemPrompt(ctx).contains("只是完整內容的一部分"))
	}

	@Test
	fun single_huge_segment_is_truncated_safely_instead_of_empty() {
		val t = transcript(segments = emptyList(), text = "這是一段非常長的單段逐字稿".repeat(40))
		val ctx = RecordingChat.buildContext(t, maxTranscriptChars = 80)
		assertEquals(1, ctx.segments.size)
		assertTrue(ctx.transcriptTruncated)
		assertTrue(ctx.coverageNote!!.contains("截斷"))
	}

	@Test
	fun build_messages_bounds_history_turns_and_chars() {
		val history = (1..10).map { i ->
			RecordingChatMessage(
				id = "m$i",
				recordingId = 42L,
				transcriptId = "t1",
				role = if (i % 2 == 1) ChatMessageRole.USER else ChatMessageRole.ASSISTANT,
				text = "訊息$i-" + "字".repeat(20),
				createdAtMillis = i.toLong(),
			)
		}
		val bounded = RecordingChat.buildMessages(history, "最後一個問題", maxHistoryTurns = 4, maxHistoryChars = 60)
		assertEquals(ChatRole.USER, bounded.last().role)
		assertEquals("最後一個問題", bounded.last().content)
		// Keeps newest messages within char budget (24 chars each -> 2 history msgs + 1 new = 3)
		assertEquals(3, bounded.size)
		assertTrue(bounded[0].content.startsWith("訊息9-"))
		assertTrue(bounded[1].content.startsWith("訊息10-"))
	}

	@Test
	fun parse_verifies_real_citations_rejects_fakes_and_detects_not_found() {
		val ctx = RecordingChat.buildContext(transcript())
		val parsed = RecordingChat.parse("Amy 說下週三前交報價〔S2〕，誤引〔S9〕", ctx)
		assertEquals(listOf(ChatCitation(2, 5_000L)), parsed.citations)
		assertEquals(1, parsed.unverified)
		assertFalse(parsed.notFound)

		val nf = RecordingChat.parse(RecordingChat.NOT_FOUND, ctx)
		assertTrue(nf.notFound)
	}

	@Test
	fun estimator_computes_tokens_and_cost_with_or_without_prices() {
		val ctx = RecordingChat.buildContext(transcript(), summary())
		val unpriced = RecordingChat.estimateRequest(ctx, emptyList(), "重點是什麼？", "gemini-3.8-flash", emptyList())
		assertTrue(unpriced.promptTokens > 0)
		assertFalse(unpriced.unitPriceConfigured)
		assertNull(unpriced.estimatedCostUsd)

		val priced = RecordingChat.estimateRequest(
			context = ctx,
			history = emptyList(),
			draftQuestion = "重點是什麼？",
			model = "gemini-3.8-flash",
			prices = listOf(ModelPrice("gemini-3.8-flash", inputPerMillion = "0.5", outputPerMillion = "1.5")),
		)
		assertTrue(priced.unitPriceConfigured)
		assertNotNull(priced.estimatedCostUsd)
		assertTrue(priced.estimatedCostUsd!! > 0.0)
	}

	@Test
	fun chat_history_round_trips_and_clears_in_transcript_store() {
		val dir = Files.createTempDirectory("chat-store").toFile()
		try {
			val store = TranscriptStore(dir)
			assertTrue(store.chatHistory(42L).isEmpty())
			val msg1 = RecordingChatMessage("c1", 42L, "t1", ChatMessageRole.USER, "重點是什麼？", createdAtMillis = 10L)
			val msg2 = RecordingChatMessage(
				id = "c2",
				recordingId = 42L,
				transcriptId = "t1",
				role = ChatMessageRole.ASSISTANT,
				text = "下週三前交報價〔S2〕",
				citations = listOf(ChatCitation(2, 5_000L)),
				createdAtMillis = 20L,
				model = "gemini-3.8-flash",
			)
			store.saveChatHistory(42L, listOf(msg1, msg2))
			assertEquals(listOf(msg1, msg2), store.chatHistory(42L))
			assertTrue(store.chatHistory(99L).isEmpty())

			store.clearChatHistory(42L)
			assertTrue(store.chatHistory(42L).isEmpty())
		} finally {
			dir.deleteRecursively()
		}
	}

	@Test
	fun openai_and_gemini_clients_send_multi_turn_chat_respect_reasoning_policy_and_record_usage() {
		val dir = Files.createTempDirectory("chat-usage").toFile()
		try {
			val usageStore = TokenUsageStore(dir)
			TokenUsageHooks.store = usageStore

			var capturedBody = ""
			val openAiTransport = HttpTransport { _, _, body, _ ->
				capturedBody = body
				HttpResponse(
					200,
					"""{"choices":[{"message":{"content":"下週三交報價〔S2〕"}}],"usage":{"prompt_tokens":120,"completion_tokens":18}}""",
				)
			}
			val openAiSettings = CloudSettings(
				providerType = ProviderType.OPENAI_COMPATIBLE,
				baseUrl = "https://api.example/v1",
				summaryModel = "gemini-3.8-flash",
				reasoningEffort = ReasoningEffort.LOW,
			)
			val client = CloudAiClients.create(openAiSettings, "secret-key", openAiTransport, openAiSettings.summaryModel).boundTo(42L)
			val outcome = client.completeChat(
				messages = listOf(
					ChatMessage(ChatRole.USER, "上次說什麼？"),
					ChatMessage(ChatRole.ASSISTANT, "討論報價。"),
					ChatMessage(ChatRole.USER, "何時交？"),
				),
				systemPrompt = "SYSTEM_CTX",
			)
			assertEquals(CallOutcome.Text("下週三交報價〔S2〕"), outcome)
			val root = Json.parseToJsonElement(capturedBody).jsonObject
			assertEquals("low", root["reasoning_effort"]!!.jsonPrimitive.content)
			val msgs = root["messages"]!!.jsonArray
			assertEquals("system", msgs[0].jsonObject["role"]!!.jsonPrimitive.content)
			assertEquals("user", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
			assertEquals("assistant", msgs[2].jsonObject["role"]!!.jsonPrimitive.content)
			assertEquals("user", msgs[3].jsonObject["role"]!!.jsonPrimitive.content)

			// Flash-lite / transcribe skips reasoning_effort per ReasoningEffortPolicy
			val flashLite = openAiSettings.copy(summaryModel = "gemini-2.5-flash-lite")
			CloudAiClients.create(flashLite, "secret-key", openAiTransport, flashLite.summaryModel)
				.completeChat(listOf(ChatMessage(ChatRole.USER, "hi")), "sys")
			assertFalse(Json.parseToJsonElement(capturedBody).jsonObject.containsKey("reasoning_effort"))

			// Gemini native uses systemInstruction + user/model contents and records usage
			var geminiBody = ""
			val geminiTransport = HttpTransport { _, _, body, _ ->
				geminiBody = body
				HttpResponse(
					200,
					"""{"candidates":[{"content":{"parts":[{"text":"預算五十萬〔S3〕"}]}}],"usageMetadata":{"promptTokenCount":80,"candidatesTokenCount":12}}""",
				)
			}
			val geminiSettings = openAiSettings.copy(providerType = ProviderType.GEMINI_NATIVE, baseUrl = "https://generativelanguage.googleapis.com/v1beta")
			val gOut = CloudAiClients.create(geminiSettings, "secret-key", geminiTransport, geminiSettings.summaryModel)
				.boundTo(42L)
				.completeChat(listOf(ChatMessage(ChatRole.USER, "預算？")), "SYS")
			assertEquals(CallOutcome.Text("預算五十萬〔S3〕"), gOut)
			val gRoot = Json.parseToJsonElement(geminiBody).jsonObject
			assertEquals("SYS", gRoot["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
			assertEquals("user", gRoot["contents"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
			assertFalse(gRoot.containsKey("reasoning_effort"))

			val events = usageStore.eventsForRecording(42L)
			assertEquals(2, events.size)
			assertEquals("chat", events[0].operation)
			assertEquals(120L, events[0].promptTokens)
			assertEquals(80L, events[1].promptTokens)
		} finally {
			dir.deleteRecursively()
		}
	}
}
