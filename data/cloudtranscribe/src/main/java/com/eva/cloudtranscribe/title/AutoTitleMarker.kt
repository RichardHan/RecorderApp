package com.eva.cloudtranscribe.title

import kotlinx.serialization.Serializable

/** Per-recording marker for automatic AI naming. Stored beside the recording's transcripts, not in Room. */
@Serializable
data class AutoTitleMarker(
	val applied: Boolean = false,
	val previousDisplayName: String? = null,
	val displayName: String? = null,
	val source: String? = null,
	val requestCount: Int = 0,
	val requestAtMillis: List<Long> = emptyList(),
	val updatedAtMillis: Long = 0,
)
