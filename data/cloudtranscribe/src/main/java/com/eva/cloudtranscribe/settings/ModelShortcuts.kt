package com.eva.cloudtranscribe.settings

import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.model.CloudSettings

/**
 * Quick-fill model IDs for the AI settings fields, and the recently-used list rules.
 * Presets reuse this app's defaults plus documented / well-known official IDs only.
 */
object ModelShortcuts {
	const val RECENT_LIMIT = 5
	const val MAX_MODEL_LENGTH = 128

	val TRANSCRIBE_PRESETS = listOf(
		CloudSettings.DEFAULT_MODEL,
		"gemini-3.5-transcribe",
		"gpt-4o-transcribe",
		"gpt-4o-mini-transcribe",
		"whisper-1",
	)

	val SUMMARY_PRESETS = listOf(
		CloudSettings.DEFAULT_MODEL,
		LiveCloudSettings.DEFAULT_SUMMARY_MODEL,
		"gpt-4o",
		"gpt-4o-mini",
	)

	/** Trimmed value, or null when blank. Over-long IDs are capped so prefs stay bounded. */
	fun normalize(value: String): String? {
		val trimmed = value.trim()
		if (trimmed.isEmpty()) return null
		return trimmed.take(MAX_MODEL_LENGTH)
	}

	/** Newest first, distinct, at most [limit]. Blank values are ignored. */
	fun record(recent: List<String>, value: String, limit: Int = RECENT_LIMIT): List<String> {
		val next = normalize(value) ?: return sanitize(recent, limit)
		return sanitize(listOf(next) + recent, limit)
	}

	fun sanitize(recent: List<String>, limit: Int = RECENT_LIMIT): List<String> =
		recent.mapNotNull(::normalize).distinct().take(limit.coerceAtLeast(0))

	/** Recents first (newest first), then presets that are not already in that list. */
	fun chips(recent: List<String>, presets: List<String>, limit: Int = RECENT_LIMIT): List<String> {
		val head = sanitize(recent, limit)
		val seen = head.toSet()
		return head + presets.mapNotNull(::normalize).filter { it !in seen }.distinct()
	}
}
