package com.eva.cloudtranscribe.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageParserTests {

	@Test
	fun openAiUsage_readsPromptAndCompletion() {
		val usage = TokenUsageParser.fromOpenAi(
			"""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":1200,"completion_tokens":80,"total_tokens":1280}}""",
		)
		assertEquals(TokenUsage(1200, 80, 1280), usage)
	}

	@Test
	fun openAiUsage_acceptsInputOutputAliases() {
		val usage = TokenUsageParser.fromOpenAi("""{"usage":{"input_tokens":10,"output_tokens":4}}""")
		assertEquals(TokenUsage(10, 4, null), usage)
	}

	@Test
	fun geminiUsageMetadata_includesThoughtTokensInCompletion() {
		val usage = TokenUsageParser.fromGemini(
			"""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}],"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"thoughtsTokenCount":5,"totalTokenCount":125}}""",
		)
		assertEquals(TokenUsage(100, 25, 125), usage)
	}

	@Test
	fun missingOrEmptyUsage_returnsNull() {
		assertNull(TokenUsageParser.fromOpenAi("""{"choices":[]}"""))
		assertNull(TokenUsageParser.fromOpenAi("""{"usage":{"prompt_tokens":0,"completion_tokens":0}}"""))
		assertNull(TokenUsageParser.fromGemini("""{"candidates":[]}"""))
		assertNull(TokenUsageParser.fromOpenAi("not-json"))
		assertNull(TokenUsageParser.fromGemini("{"))
	}

	@Test
	fun fromBody_fallsBackAcrossShapes() {
		val geminiInOpenAiClient = TokenUsageParser.fromBody(
			"""{"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":1}}""",
			gemini = false,
		)
		assertEquals(TokenUsage(3, 1, null), geminiInOpenAiClient)
		val openAiInGeminiClient = TokenUsageParser.fromBody(
			"""{"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}""",
			gemini = true,
		)
		assertEquals(TokenUsage(9, 2, 11), openAiInGeminiClient)
	}

	@Test
	fun stringAndFractionalNumbers_areAccepted() {
		val usage = TokenUsageParser.fromOpenAi("""{"usage":{"prompt_tokens":"15","completion_tokens":2.0}}""")
		assertEquals(TokenUsage(15, 2, null), usage)
	}
}
