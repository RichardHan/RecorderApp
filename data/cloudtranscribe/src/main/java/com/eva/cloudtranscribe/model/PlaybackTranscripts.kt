package com.eva.cloudtranscribe.model

/** One line of a saved transcript; [startMs]/[endMs] are only set when the record holds real audio timestamps. */
data class PlaybackLine(val text: String, val startMs: Long? = null, val endMs: Long? = null)

/** Pure helpers for showing already-saved (immutable) transcripts on the playback screen. Never triggers transcription. */
object PlaybackTranscripts {

	private fun usable(r: TranscriptRecord) = r.text.isNotBlank() || r.segments.any { it.text.isNotBlank() }

	/** Default variant: newest finished cloud transcript, then newest finished offline, then newest with any text. */
	fun defaultSelection(records: List<TranscriptRecord>): TranscriptRecord? {
		val sorted = records.sortedByDescending { it.createdAtMillis }
		val finished = sorted.filter { usable(it) && (it.status == RunStatus.SUCCESS || it.status == RunStatus.PARTIAL) }
		return finished.firstOrNull { it.source == TranscriptSource.CLOUD }
			?: finished.firstOrNull()
			?: sorted.firstOrNull { usable(it) }
	}

	/** Records worth listing (have text), newest first. */
	fun selectable(records: List<TranscriptRecord>): List<TranscriptRecord> =
		records.filter(::usable).sortedByDescending { it.createdAtMillis }

	/**
	 * Timed lines from real per-segment timestamps; returns null when the record has no usable timestamps
	 * (caller then shows scrollable full text without fake sync).
	 */
	fun timedLines(record: TranscriptRecord): List<PlaybackLine>? {
		val segs = record.segments.sortedBy { it.startMs }
			.flatMap { s -> if (s.parts.isNotEmpty()) s.parts.sortedBy { it.startMs } else listOf(s) }
			.filter { it.status == SegmentStatus.OK && it.text.isNotBlank() && it.endMs > it.startMs && it.startMs >= 0 }
		if (segs.isEmpty()) return null
		return segs.map { PlaybackLine(it.text.trim(), it.startMs, it.endMs) }
	}

	fun lines(record: TranscriptRecord): List<PlaybackLine> =
		timedLines(record) ?: record.text.lines().filter { it.isNotBlank() }.map { PlaybackLine(it.trim()) }

	/**
	 * On-device search inside one transcript: indices of lines containing [query] (case-insensitive, whitespace-trimmed).
	 * Blank query → no matches. Pure and local; never sends anything anywhere.
	 */
	fun search(lines: List<PlaybackLine>, query: String): List<Int> {
		val q = query.trim()
		if (q.isEmpty()) return emptyList()
		return lines.indices.filter { lines[it].text.contains(q, ignoreCase = true) }
	}

	/** Next match index (wrapping) after [current] in direction [forward]; -1 when there are no matches. */
	fun stepMatch(matches: List<Int>, current: Int, forward: Boolean): Int {
		if (matches.isEmpty()) return -1
		val n = matches.size
		val pos = matches.indexOf(current)
		return when {
			pos < 0 -> if (forward) matches.first() else matches.last()
			forward -> matches[(pos + 1) % n]
			else -> matches[(pos - 1 + n) % n]
		}
	}

	/** Index of the timed line covering [positionMs]; -1 when none or when lines are untimed. */
	fun activeIndex(lines: List<PlaybackLine>, positionMs: Long): Int =
		lines.indexOfFirst { l -> l.startMs != null && l.endMs != null && positionMs >= l.startMs && positionMs < l.endMs }

	fun label(r: TranscriptRecord): String {
		val src = if (r.source == TranscriptSource.CLOUD) "雲端" else "離線"
		val partial = when (r.status) { RunStatus.SUCCESS -> ""; RunStatus.PARTIAL -> "・部分"; else -> "・${r.status.name}" }
		return "$src・${r.provenance.model}$partial"
	}
}
