package com.eva.cloudtranscribe.usage

import com.eva.cloudtranscribe.store.AtomicTextFile
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Append-only local log of token usage events (`<root>/v1/token-usage.jsonl`).
 * Tests can use any directory; production uses the same root as [com.eva.cloudtranscribe.store.TranscriptStore].
 */
class TokenUsageStore(root: File) {
	private val dir = File(root, "v1")
	private val file = File(dir, "token-usage.jsonl")
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val lock = Any()

	fun append(event: TokenUsageEvent) {
		if (event.billedTokens <= 0L && event.promptTokens <= 0L && event.completionTokens <= 0L) return
		val line = json.encodeToString(TokenUsageEvent.serializer(), event)
		synchronized(lock) {
			dir.mkdirs()
			file.appendText(line + "\n")
		}
	}

	fun events(): List<TokenUsageEvent> = synchronized(lock) {
		if (!file.exists()) return emptyList()
		file.readLines().mapNotNull { line ->
			val trimmed = line.trim()
			if (trimmed.isEmpty()) null
			else runCatching { json.decodeFromString(TokenUsageEvent.serializer(), trimmed) }.getOrNull()
		}
	}

	fun eventsBetween(startInclusive: Long, endExclusive: Long): List<TokenUsageEvent> =
		events().filter { it.atMillis in startInclusive until endExclusive }

	fun eventsForRecording(recordingId: Long): List<TokenUsageEvent> =
		events().filter { it.recordingId == recordingId }
}

/** Process-wide sink used by API clients after a successful HTTP body is parsed. Null in unit tests unless set. */
object TokenUsageHooks {
	@Volatile var store: TokenUsageStore? = null
	private val recordingId = ThreadLocal<Long?>()

	fun <T> withRecording(id: Long?, block: () -> T): T {
		val previous = recordingId.get()
		if (id != null && id > 0L) recordingId.set(id) else recordingId.remove()
		return try {
			block()
		} finally {
			if (previous != null) recordingId.set(previous) else recordingId.remove()
		}
	}

	suspend fun <T> withRecordingSuspend(id: Long?, block: suspend () -> T): T =
		withContext(recordingId.asContextElement(id?.takeIf { it > 0L })) { block() }

	fun currentRecordingId(): Long? = recordingId.get()

	fun record(
		model: String,
		usage: TokenUsage?,
		provider: String? = null,
		operation: String? = null,
		atMillis: Long = System.currentTimeMillis(),
		recordingId: Long? = currentRecordingId(),
	) {
		val tokens = usage?.takeIf { it.hasCounts } ?: return
		store?.append(
			TokenUsageEvent(
				atMillis = atMillis,
				model = model,
				promptTokens = tokens.promptTokens,
				completionTokens = tokens.completionTokens,
				totalTokens = tokens.totalTokens,
				recordingId = recordingId?.takeIf { it > 0L },
				provider = provider,
				operation = operation,
			),
		)
	}

	internal fun resetForTests() {
		store = null
		recordingId.remove()
	}
}

/** Ensures token events recorded on this client are tagged with [recordingId] (same thread as send()). */
class RecordingBoundClient(
	private val inner: com.eva.cloudtranscribe.api.CloudAiClient,
	private val recordingId: Long,
) : com.eva.cloudtranscribe.api.CloudAiClient {
	override fun transcribe(prompt: String, audio: com.eva.cloudtranscribe.api.AudioPayload) =
		TokenUsageHooks.withRecording(recordingId) { inner.transcribe(prompt, audio) }

	override fun complete(prompt: String) =
		TokenUsageHooks.withRecording(recordingId) { inner.complete(prompt) }

	override fun completeChat(messages: List<com.eva.cloudtranscribe.api.ChatMessage>, systemPrompt: String?) =
		TokenUsageHooks.withRecording(recordingId) { inner.completeChat(messages, systemPrompt) }
}

fun com.eva.cloudtranscribe.api.CloudAiClient.boundTo(recordingId: Long?): com.eva.cloudtranscribe.api.CloudAiClient =
	if (recordingId == null || recordingId <= 0L) this else RecordingBoundClient(this, recordingId)
