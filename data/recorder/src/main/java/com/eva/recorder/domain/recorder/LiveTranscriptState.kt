package com.eva.recorder.domain.recorder

/** What the live on-device recogniser is doing right now; drives the visible status line. */
enum class LiveTranscriptStatus {
	/** Transcription off or no model selected. */
	DISABLED,
	/** Switch on but no downloaded model selected. */
	NO_MODEL,
	LOADING,
	/** Listening; sentence-mode engines only emit text after a pause. */
	LISTENING,
	DECODING,
	ERROR,
}

/**
 * Full live transcript snapshot: every finalized line (never truncated) plus the current partial
 * (streaming engines only) and status/warning message.
 */
data class LiveTranscriptState(
	val finalized: List<String> = emptyList(),
	val partial: String = "",
	val status: LiveTranscriptStatus = LiveTranscriptStatus.DISABLED,
	val message: String? = null,
	val modelLabel: String? = null,
) {
	val hasText: Boolean get() = finalized.isNotEmpty() || partial.isNotBlank()
}
