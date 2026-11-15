package com.eva.cloudtranscribe.model

import kotlinx.serialization.Serializable

/** Wire protocol used to reach the user's configured endpoint. No automatic fallback between them. */
@Serializable
enum class ProviderType { OPENAI_COMPATIBLE, GEMINI_NATIVE }

@Serializable
enum class ReasoningEffort(val wire: String?) { OFF(null), LOW("low"), MEDIUM("medium"), HIGH("high") }

/** Non-secret settings. The API key is stored separately in [com.eva.cloudtranscribe.secure.ApiKeyStore]. */
@Serializable
data class CloudSettings(
	val providerType: ProviderType = ProviderType.OPENAI_COMPATIBLE,
	val baseUrl: String = "",
	val transcribeModel: String = DEFAULT_MODEL,
	val summaryModel: String = DEFAULT_MODEL,
	val reasoningEffort: ReasoningEffort = ReasoningEffort.LOW,
	val chunkSeconds: Int = DEFAULT_CHUNK_SECONDS,
	val workers: Int = DEFAULT_WORKERS,
	val autoAfterRecording: Boolean = false,
	val autoWifiOnly: Boolean = true,
	val hallucinationWarning: Boolean = true,
	/** Skip upload of chunks that measure as near-silent. Off by default: quiet speech must not be lost. */
	val skipSilentChunks: Boolean = false,
	/** Shape of NEW post-recording summaries. Existing summaries are never rewritten by changing this. */
	val summaryFormat: SummaryFormat = SummaryFormat.CONCISE,
	/** Used only when [summaryFormat] is [SummaryFormat.CUSTOM]; blank falls back to the concise format. */
	val customSummaryInstructions: String = "",
	/** Spelling hints for CLOUD transcription only (empty = prompt unchanged). Sent with audio only when cloud transcription runs. */
	val customVocabulary: List<VocabularyTerm> = emptyList(),
) {
	val normalizedChunkSeconds: Int get() = chunkSeconds.coerceIn(MIN_CHUNK_SECONDS, MAX_CHUNK_SECONDS)

	/** Format actually used for a new summary; a blank custom prompt degrades to the concise default. */
	val effectiveSummaryFormat: SummaryFormat
		get() = if (summaryFormat == SummaryFormat.CUSTOM && customSummaryInstructions.isBlank()) SummaryFormat.CONCISE else summaryFormat
	val normalizedWorkers: Int get() = workers.coerceIn(1, MAX_WORKERS)

	/** Returns a human readable problem or null when usable. */
	fun validate(hasApiKey: Boolean): String? = when {
		baseUrl.isBlank() -> "尚未設定 API Endpoint"
		!baseUrl.trim().startsWith("https://", ignoreCase = true) -> "Endpoint 必須是 https://"
		transcribeModel.isBlank() -> "尚未設定模型名稱"
		!hasApiKey -> "尚未設定 API Key"
		else -> null
	}

	companion object {
		const val DEFAULT_MODEL = "gemini-3.8-flash"
		const val DEFAULT_CHUNK_SECONDS = 300
		const val MIN_CHUNK_SECONDS = 60
		const val MAX_CHUNK_SECONDS = 900
		/** Desktop pipeline used 6; phones default to 2 to bound memory, heat and concurrent mobile uploads. */
		const val DEFAULT_WORKERS = 2
		const val MAX_WORKERS = 6
		const val MAX_SUMMARY_INSTRUCTIONS = 1200
		const val MAX_ATTEMPTS = 3
		const val MAX_TOKENS = 16384
		const val TIMEOUT_SECONDS = 120
	}
}

@Serializable
enum class TranscriptSource { OFFLINE, CLOUD }

@Serializable
enum class RunStatus { QUEUED, RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED }

/**
 * Shape of a post-recording summary. Only the instructions change; every variant keeps the same rules (no invented
 * owner/deadline, omit what was not said, answer [com.eva.cloudtranscribe.engine.Prompts.NO_KEY_POINTS] when empty).
 */
@Serializable
enum class SummaryFormat { CONCISE, DECISIONS_AND_ACTIONS, BULLETS_AND_OPEN_QUESTIONS, CUSTOM }

@Serializable
enum class SegmentStatus { OK, SILENT, CONTENT_FILTERED, EMPTY, FAILED }

@Serializable
data class SegmentResult(
	val index: Int,
	val startMs: Long,
	val endMs: Long,
	val status: SegmentStatus,
	val text: String = "",
	val attempts: Int = 0,
	val error: String? = null,
	/** For content-filter splits: sub-range results in order. */
	val parts: List<SegmentResult> = emptyList(),
) {
	val isFinal: Boolean get() = status == SegmentStatus.OK || status == SegmentStatus.SILENT
}

/** Provenance for a transcript: which engine, model and settings produced it. */
@Serializable
data class Provenance(
	val engine: String,
	val providerType: ProviderType? = null,
	val endpointHost: String? = null,
	val model: String,
	val reasoningEffort: String? = null,
	val chunkSeconds: Int? = null,
	val workers: Int? = null,
	val audioFormat: String? = null,
	val trigger: String = "manual",
	/** [SummaryFormat] name for summaries; null for transcripts and for summaries made before formats existed. */
	val summaryFormat: String? = null,
)

/**
 * One transcript of one recording. Offline (on-device) and cloud transcripts share this schema so
 * they can be listed and compared side-by-side; neither ever overwrites the other.
 */
@Serializable
data class TranscriptRecord(
	val id: String,
	val recordingId: Long,
	val source: TranscriptSource,
	val provenance: Provenance,
	val createdAtMillis: Long,
	val updatedAtMillis: Long = createdAtMillis,
	val status: RunStatus,
	val text: String = "",
	val totalSegments: Int = 0,
	val completedSegments: Int = 0,
	val segments: List<SegmentResult> = emptyList(),
	val warnings: List<String> = emptyList(),
	val requestCount: Int = 0,
	val error: String? = null,
)

@Serializable
data class SummaryRecord(
	val id: String,
	val recordingId: Long,
	val sourceTranscriptId: String,
	val provenance: Provenance,
	val createdAtMillis: Long,
	val status: RunStatus,
	val text: String = "",
	val error: String? = null,
)
