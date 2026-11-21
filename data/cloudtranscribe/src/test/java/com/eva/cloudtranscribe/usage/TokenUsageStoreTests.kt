package com.eva.cloudtranscribe.usage

import com.eva.cloudtranscribe.api.AudioPayload
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.HttpResponse
import com.eva.cloudtranscribe.api.HttpTransport
import com.eva.cloudtranscribe.api.OpenAiCompatibleClient
import com.eva.cloudtranscribe.model.ReasoningEffort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TokenUsageStoreTests {
	@get:Rule val tmp = TemporaryFolder()

	@After
	fun tearDown() {
		TokenUsageHooks.resetForTests()
	}

	@Test
	fun append_readsPersistedEventsAndSkipsCorruptLines() {
		val store = TokenUsageStore(tmp.root)
		store.append(TokenUsageEvent(10L, "m", 11, 2, 13, recordingId = 7, provider = "openai_compatible", operation = "complete"))
		val file = java.io.File(tmp.root, "v1/token-usage.jsonl")
		file.appendText("{not-json}\n\n")
		store.append(TokenUsageEvent(20L, "n", 1, 0))
		val events = store.events()
		assertEquals(2, events.size)
		assertEquals(7L, events[0].recordingId)
		assertEquals("n", events[1].model)
		assertEquals(1, store.eventsForRecording(7).size)
		assertEquals(1, store.eventsBetween(15, 25).size)
	}

	@Test
	fun hooks_recordSuccessfulOpenAiResponsesAgainstTheBoundRecording() {
		val store = TokenUsageStore(tmp.root)
		TokenUsageHooks.store = store
		val transport = HttpTransport { _, _, _, _ ->
			HttpResponse(200, """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":40,"completion_tokens":5,"total_tokens":45}}""")
		}
		val client = OpenAiCompatibleClient("https://example.test/v1", "k", "gemini-3.8-flash", ReasoningEffort.OFF, transport)
			.boundTo(99L)
		assertEquals(CallOutcome.Text("ok"), client.complete("hi"))
		val events = store.events()
		assertEquals(1, events.size)
		assertEquals(99L, events[0].recordingId)
		assertEquals("gemini-3.8-flash", events[0].model)
		assertEquals(40L, events[0].promptTokens)
		assertEquals(5L, events[0].completionTokens)
		assertEquals("complete", events[0].operation)
		assertEquals("openai_compatible", events[0].provider)
	}

	@Test
	fun hooks_ignoreResponsesWithoutUsage() {
		val store = TokenUsageStore(tmp.root)
		TokenUsageHooks.store = store
		val transport = HttpTransport { _, _, _, _ ->
			HttpResponse(200, """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
		}
		OpenAiCompatibleClient("https://example.test/v1", "k", "m", ReasoningEffort.OFF, transport).complete("hi")
		assertTrue(store.events().isEmpty())
	}
}
