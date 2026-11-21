package com.eva.cloudtranscribe.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Extracts token counts from provider JSON. Never contacts a network.
 * Accepts OpenAI-compatible `usage` and Gemini `usageMetadata` shapes, including common aliases.
 */
object TokenUsageParser {
	private val json = Json { ignoreUnknownKeys = true }

	fun fromOpenAi(body: String): TokenUsage? = fromOpenAiElement(
		runCatching { json.parseToJsonElement(body).jsonObject["usage"] as? JsonObject }.getOrNull(),
	)

	fun fromGemini(body: String): TokenUsage? = fromGeminiElement(
		runCatching { json.parseToJsonElement(body).jsonObject["usageMetadata"] as? JsonObject }.getOrNull(),
	)

	fun fromBody(body: String, gemini: Boolean): TokenUsage? =
		if (gemini) fromGemini(body) ?: fromOpenAi(body) else fromOpenAi(body) ?: fromGemini(body)

	internal fun fromOpenAiElement(usage: JsonObject?): TokenUsage? {
		usage ?: return null
		val prompt = longOf(usage, "prompt_tokens", "input_tokens", "promptTokenCount")
		val completion = longOf(usage, "completion_tokens", "output_tokens", "completionTokenCount")
		val total = longOf(usage, "total_tokens", "totalTokenCount").takeIf { it > 0 }
		val parsed = TokenUsage(prompt, completion, total)
		return parsed.takeIf { it.hasCounts }
	}

	internal fun fromGeminiElement(meta: JsonObject?): TokenUsage? {
		meta ?: return null
		val prompt = longOf(meta, "promptTokenCount", "prompt_tokens", "inputTokenCount")
		val candidates = longOf(meta, "candidatesTokenCount", "candidateTokenCount", "outputTokenCount", "completion_tokens")
		val thoughts = longOf(meta, "thoughtsTokenCount", "thoughtTokenCount")
		val completion = candidates + thoughts
		val total = longOf(meta, "totalTokenCount", "total_tokens").takeIf { it > 0 }
		val parsed = TokenUsage(prompt, completion, total)
		return parsed.takeIf { it.hasCounts }
	}

	private fun longOf(obj: JsonObject, vararg keys: String): Long {
		keys.forEach { key ->
			val value = obj[key] as? JsonPrimitive ?: return@forEach
			value.longOrNull?.takeIf { it >= 0 }?.let { return it }
			value.contentOrNull?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
			value.contentOrNull?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }?.toLong()?.let { return it }
		}
		return 0L
	}
}
