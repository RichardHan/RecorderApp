package com.eva.cloudtranscribe.api

import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.ProviderType
import com.eva.cloudtranscribe.model.ReasoningEffort
import java.io.IOException
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Outcome of one billable request. Network errors and empty bodies are never reported as silence. */
sealed interface CallOutcome {
	data class Text(val text: String) : CallOutcome
	data object Empty : CallOutcome
	data object ContentFiltered : CallOutcome
	data class Failure(val message: String, val retryable: Boolean, val httpCode: Int? = null) : CallOutcome
}

data class AudioPayload(val bytes: ByteArray, val format: String = "mp3") {
	val mimeType: String get() = "audio/$format"
}

/** Extension point: any audio-capable provider (cloud or a future local HTTP server) implements this. */
interface CloudAiClient {
	fun transcribe(prompt: String, audio: AudioPayload): CallOutcome
	fun complete(prompt: String): CallOutcome
}

object CloudAiClients {
	fun create(settings: CloudSettings, apiKey: String, transport: HttpTransport, model: String): CloudAiClient =
		when (settings.providerType) {
			ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(settings.baseUrl, apiKey, model, settings.reasoningEffort, transport)
			ProviderType.GEMINI_NATIVE -> GeminiNativeClient(settings.baseUrl, apiKey, model, transport)
		}
}

private val json = Json { ignoreUnknownKeys = true }

private fun classifyHttp(code: Int, body: String, apiKey: String): CallOutcome.Failure {
	val retryable = code == 408 || code == 429 || code in 500..599
	return CallOutcome.Failure("HTTP $code: ${Redactor.redact(body, apiKey)}", retryable, code)
}

private inline fun safeCall(apiKey: String, block: () -> CallOutcome): CallOutcome = try {
	block()
} catch (e: IOException) {
	CallOutcome.Failure("網路錯誤: ${Redactor.redact(e.message ?: e.javaClass.simpleName, apiKey)}", retryable = true)
} catch (e: kotlinx.serialization.SerializationException) {
	CallOutcome.Failure("回應格式錯誤: ${Redactor.redact(e.message, apiKey)}", retryable = true)
} catch (e: IllegalArgumentException) {
	CallOutcome.Failure("設定錯誤: ${Redactor.redact(e.message, apiKey)}", retryable = false)
}

/**
 * OpenAI-compatible `/chat/completions` (e.g. the company RONE endpoint).
 * Audio is sent as `{"type":"input_audio","input_audio":{"data":<b64>,"format":"mp3"}}`.
 */
class OpenAiCompatibleClient(
	baseUrl: String,
	private val apiKey: String,
	private val model: String,
	private val effort: ReasoningEffort,
	private val transport: HttpTransport,
	private val maxTokens: Int = CloudSettings.MAX_TOKENS,
	private val timeoutSeconds: Int = CloudSettings.TIMEOUT_SECONDS,
) : CloudAiClient {
	private val url = baseUrl.trim().trimEnd('/') + "/chat/completions"

	/** Mirrors the desktop router: no reasoning_effort for `*transcribe*` / `*flash-lite*` models. */
	fun shouldSendEffort(): Boolean = effort.wire != null &&
		!model.contains("transcribe", ignoreCase = true) &&
		!model.contains("flash-lite", ignoreCase = true)

	fun buildBody(content: JsonElement): String = buildJsonObject {
		put("model", model)
		putJsonArray("messages") {
			addJsonObject {
				put("role", "user")
				put("content", content)
			}
		}
		put("max_tokens", maxTokens)
		put("stream", false)
		if (shouldSendEffort()) put("reasoning_effort", effort.wire)
	}.toString()

	fun audioContent(prompt: String, audio: AudioPayload): JsonArray = JsonArray(
		listOf(
			buildJsonObject { put("type", "text"); put("text", prompt) },
			buildJsonObject {
				put("type", "input_audio")
				putJsonObject("input_audio") {
					put("data", Base64.getEncoder().encodeToString(audio.bytes))
					put("format", audio.format)
				}
			},
		),
	)

	override fun transcribe(prompt: String, audio: AudioPayload): CallOutcome =
		send(buildBody(audioContent(prompt, audio)))

	override fun complete(prompt: String): CallOutcome = send(buildBody(JsonPrimitive(prompt)))

	private fun send(body: String): CallOutcome = safeCall(apiKey) {
		val response = transport.postJson(url, mapOf("Authorization" to "Bearer $apiKey"), body, timeoutSeconds)
		if (response.code !in 200..299) return@safeCall classifyHttp(response.code, response.body, apiKey)
		parse(response.body)
	}

	companion object {
		fun parse(body: String): CallOutcome {
			val root = json.parseToJsonElement(body).jsonObject
			val choices = root["choices"] as? JsonArray
			if (choices.isNullOrEmpty()) return CallOutcome.Empty
			val first = choices[0].jsonObject
			val finish = (first["finish_reason"] as? JsonPrimitive)?.contentOrNull
			if (finish == "content_filter") return CallOutcome.ContentFiltered
			val message = first["message"] as? JsonObject ?: return CallOutcome.Empty
			val text = when (val c = message["content"]) {
				is JsonPrimitive -> c.contentOrNull.orEmpty()
				is JsonArray -> c.joinToString("") { part ->
					(part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
				}
				else -> ""
			}.trim()
			return if (text.isEmpty()) CallOutcome.Empty else CallOutcome.Text(text)
		}
	}
}

/**
 * Gemini native REST `models/{model}:generateContent` with `inline_data` audio.
 * Schema follows the public Gemini API docs; not verified against a live key from this build.
 */
class GeminiNativeClient(
	baseUrl: String,
	private val apiKey: String,
	private val model: String,
	private val transport: HttpTransport,
	private val maxTokens: Int = CloudSettings.MAX_TOKENS,
	private val timeoutSeconds: Int = CloudSettings.TIMEOUT_SECONDS,
) : CloudAiClient {
	private val url = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE } + "/models/$model:generateContent"

	fun buildBody(prompt: String, audio: AudioPayload?): String = buildJsonObject {
		putJsonArray("contents") {
			addJsonObject {
				put("role", "user")
				putJsonArray("parts") {
					addJsonObject { put("text", prompt) }
					if (audio != null) addJsonObject {
						putJsonObject("inline_data") {
							put("mime_type", audio.mimeType)
							put("data", Base64.getEncoder().encodeToString(audio.bytes))
						}
					}
				}
			}
		}
		putJsonObject("generationConfig") { put("maxOutputTokens", maxTokens) }
	}.toString()

	override fun transcribe(prompt: String, audio: AudioPayload) = send(buildBody(prompt, audio))
	override fun complete(prompt: String) = send(buildBody(prompt, null))

	private fun send(body: String): CallOutcome = safeCall(apiKey) {
		val response = transport.postJson(url, mapOf("x-goog-api-key" to apiKey), body, timeoutSeconds)
		if (response.code !in 200..299) return@safeCall classifyHttp(response.code, response.body, apiKey)
		parse(response.body)
	}

	companion object {
		const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"
		private val FILTER_REASONS = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION")

		fun parse(body: String): CallOutcome {
			val root = json.parseToJsonElement(body).jsonObject
			val block = root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
			if (block != null) return CallOutcome.ContentFiltered
			val candidates = root["candidates"] as? JsonArray
			if (candidates.isNullOrEmpty()) return CallOutcome.Empty
			val first = candidates[0].jsonObject
			val finish = first["finishReason"]?.jsonPrimitive?.contentOrNull
			val parts = first["content"]?.jsonObject?.get("parts") as? JsonArray
			val text = parts?.joinToString("") { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty() }?.trim().orEmpty()
			if (finish in FILTER_REASONS && text.isEmpty()) return CallOutcome.ContentFiltered
			return if (text.isEmpty()) CallOutcome.Empty else CallOutcome.Text(text)
		}
	}
}
