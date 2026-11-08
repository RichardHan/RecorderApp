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
) {
	val normalizedChunkSeconds: Int get() = chunkSeconds.coerceIn(MIN_CHUNK_SECONDS, MAX_CHUNK_SECONDS)
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
		const val MAX_ATTEMPTS = 3
		const val MAX_TOKENS = 16384
		const val TIMEOUT_SECONDS = 120
	}
}

@Serializable
enum class TranscriptSource { OFFLINE, CLOUD }

@Serializable
enum class RunStatus { QUEUED, RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED }

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
enum class SummaryStyle { BRIEF, MEETING_NOTES }

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
	val style: SummaryStyle = SummaryStyle.BRIEF,
	/** Exact endpoint approved when the request was queued; null for legacy summaries. */
	val approvedEndpoint: String? = null,
	/** Immutable evidence used by this summary, even if its transcript is retried later. */
	val sourceSnapshot: List<MeetingNotes.Source> = emptyList(),
	val sourceTranscriptUpdatedAtMillis: Long? = null,
)
