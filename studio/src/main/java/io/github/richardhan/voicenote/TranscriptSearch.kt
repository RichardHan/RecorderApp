package io.github.richardhan.voicenote

import java.io.File
import java.io.FileNotFoundException

internal data class TranscriptHit(val recording: File, val excerpt: String)

internal fun readTranscript(recording: File): String = try {
	transcriptFile(recording).readText()
} catch (_: FileNotFoundException) {
	""
}

internal fun findExcerpt(text: String, query: String): String? {
	if (query.isBlank()) return null
	val match = text.indexOf(query, ignoreCase = true)
	if (match < 0) return null
	val start = (match - 45).coerceAtLeast(0)
	val end = (match + query.length + 70).coerceAtMost(text.length)
	val prefix = if (start > 0) "…" else ""
	val suffix = if (end < text.length) "…" else ""
	return prefix + text.substring(start, end).replace('\n', ' ') + suffix
}

internal fun transcriptMatchOffsets(text: String, query: String): List<Int> {
	if (query.isBlank()) return emptyList()
	return buildList {
		var offset = text.indexOf(query, ignoreCase = true)
		while (offset >= 0) {
			add(offset)
			offset = text.indexOf(query, startIndex = offset + query.length, ignoreCase = true)
		}
	}
}
