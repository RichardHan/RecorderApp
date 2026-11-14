package com.eva.cloudtranscribe.model

/** Literal on-device search. Original UTF-16 offsets are retained for Compose highlights. */
object TranscriptSearch {
	fun matchingLines(lines: List<PlaybackLine>, query: String): List<Int> {
		val needle = query.trim()
		if (needle.isEmpty()) return emptyList()
		return lines.indices.filter { lines[it].text.contains(needle, ignoreCase = true) }
	}

	fun ranges(text: String, query: String): List<IntRange> {
		val needle = query.trim()
		if (needle.isEmpty()) return emptyList()
		val result = mutableListOf<IntRange>()
		var from = 0
		while (from <= text.length - needle.length) {
			val start = text.indexOf(needle, from, ignoreCase = true)
			if (start < 0) break
			result += start until start + needle.length
			from = start + needle.length
		}
		return result
	}

	/** Wrap through matching lines, never seek audio or invent timestamps. */
	fun next(current: Int, size: Int, direction: Int): Int {
		if (size <= 0) return -1
		if (current !in 0 until size) return if (direction < 0) size - 1 else 0
		return Math.floorMod(current + if (direction < 0) -1 else 1, size)
	}
}
