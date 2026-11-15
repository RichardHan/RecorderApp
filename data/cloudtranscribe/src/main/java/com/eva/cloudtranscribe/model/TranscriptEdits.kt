package com.eva.cloudtranscribe.model

import kotlinx.serialization.Serializable

/**
 * User corrections for one saved transcript, kept beside it as an overlay. The original offline/cloud record is never
 * rewritten, so it can still be compared or re-run. Keys address one displayed line; times always come from the
 * original record and are never edited or estimated here.
 */
@Serializable
data class TranscriptEdits(
	val recordingId: Long,
	val transcriptId: String,
	/** [TranscriptRecord.updatedAtMillis] of the original the edits were made against. */
	val sourceUpdatedAtMillis: Long,
	val revision: Int = 0,
	val updatedAtMillis: Long = 0L,
	/** line key -> corrected text. */
	val lines: Map<String, String> = emptyMap(),
	/** Single-step undo: the [lines] before the last save; null when there is nothing to undo. */
	val previous: Map<String, String>? = null,
)

/** One displayed transcript line together with the stable key used to store a correction for it. */
data class EditableLine(val key: String, val line: PlaybackLine)

/** Pure helpers for on-device transcript corrections. No network, no time synthesis. */
object TranscriptEditing {

	/** Transcripts still being written cannot be edited (their lines may still change). */
	fun canEdit(record: TranscriptRecord): Boolean = record.status != RunStatus.QUEUED && record.status != RunStatus.RUNNING

	/** Lines in the same order and with the same filtering as [PlaybackTranscripts.lines], each with a stable key. */
	fun editableLines(record: TranscriptRecord): List<EditableLine> =
		if (PlaybackTranscripts.timedLines(record) != null) timedKeyed(record).map { (key, seg) -> EditableLine(key, PlaybackLine(seg.text.trim(), seg.startMs, seg.endMs)) }
		else untimedKeyed(record).map { (key, text) -> EditableLine(key, PlaybackLine(text.trim())) }

	/**
	 * What the playback screen shows: [editableLines] of the original with corrections applied and blanked lines
	 * hidden. Same lines, in the same order, as `PlaybackTranscripts.lines(apply(record, edits))`, but keyed.
	 */
	fun displayLines(record: TranscriptRecord, edits: TranscriptEdits?): List<EditableLine> {
		val active = edits?.takeIf { matches(record, it) }?.lines.orEmpty()
		return editableLines(record).map { e -> active[e.key]?.let { e.copy(line = e.line.copy(text = it.trim())) } ?: e }
			.filter { it.line.text.isNotBlank() }
	}

	/** True when [edits] were made against this exact version of [record]. */
	fun matches(record: TranscriptRecord, edits: TranscriptEdits?): Boolean =
		edits != null && edits.transcriptId == record.id && edits.sourceUpdatedAtMillis == record.updatedAtMillis

	/**
	 * Returns [record] with corrected text. Ids, statuses and every start/end time are kept as they were. When the
	 * original changed since the edits were saved, the original is returned unchanged (the caller asks the user).
	 */
	fun apply(record: TranscriptRecord, edits: TranscriptEdits?): TranscriptRecord {
		if (edits == null || edits.lines.isEmpty() || !matches(record, edits)) return record
		val changed = record.copy(updatedAtMillis = maxOf(record.updatedAtMillis, edits.updatedAtMillis))
		return if (PlaybackTranscripts.timedLines(record) != null) {
			changed.copy(segments = record.segments.map { s ->
				if (s.parts.isEmpty()) edits.lines[segmentKey(s)]?.let { s.copy(text = it) } ?: s
				else {
					val sortedParts = s.parts.sortedBy { it.startMs }
					s.copy(parts = sortedParts.mapIndexed { i, p -> edits.lines[partKey(s, i)]?.let { p.copy(text = it) } ?: p })
				}
			})
		} else {
			changed.copy(text = untimedKeyed(record).map { (key, text) -> edits.lines[key] ?: text }.filter { it.isNotBlank() }.joinToString("\n"))
		}
	}

	/**
	 * Saves [newText] for line [key]. Returns null when nothing would change or the result would blank the whole
	 * transcript. The previous corrections become the single undo step.
	 */
	fun withLine(record: TranscriptRecord, current: TranscriptEdits?, key: String, newText: String, nowMillis: Long): TranscriptEdits? {
		val lines = editableLines(record)
		val original = lines.firstOrNull { it.key == key } ?: return null
		val base = current?.takeIf { matches(record, it) }
		val oldLines = base?.lines.orEmpty()
		val cleaned = newText.replace('\n', ' ').trim()
		val shownBefore = oldLines[key] ?: original.line.text
		if (cleaned == shownBefore.trim()) return null
		val next = if (cleaned == original.line.text) oldLines - key else oldLines + (key to cleaned)
		if (lines.all { (next[it.key] ?: it.line.text).isBlank() }) return null
		return TranscriptEdits(
			recordingId = record.recordingId, transcriptId = record.id, sourceUpdatedAtMillis = record.updatedAtMillis,
			revision = (base?.revision ?: current?.revision ?: 0) + 1, updatedAtMillis = nowMillis,
			lines = next, previous = oldLines,
		)
	}

	/** Restores the corrections before the last save. Null when there is no undo step. */
	fun undo(current: TranscriptEdits?, nowMillis: Long): TranscriptEdits? {
		val previous = current?.previous ?: return null
		return current.copy(revision = current.revision + 1, updatedAtMillis = nowMillis, lines = previous, previous = null)
	}

	/** Re-anchors edits to a changed original (user chose to keep their corrections). Keys that no longer exist drop. */
	fun rebase(record: TranscriptRecord, current: TranscriptEdits, nowMillis: Long): TranscriptEdits {
		val keys = editableLines(record).map { it.key }.toSet()
		return current.copy(sourceUpdatedAtMillis = record.updatedAtMillis, revision = current.revision + 1, updatedAtMillis = nowMillis,
			lines = current.lines.filterKeys { it in keys }, previous = null)
	}

	/** Replaces every literal (case-insensitive, same as search) occurrence of [query] in one line. */
	fun replaceInLine(text: String, query: String, replacement: String): String {
		val ranges = TranscriptSearch.ranges(text, query)
		if (ranges.isEmpty()) return text
		return buildString {
			var from = 0
			ranges.forEach { r -> append(text, from, r.first); append(replacement); from = r.last + 1 }
			append(text, from, text.length)
		}
	}

	private fun segmentKey(s: SegmentResult) = "s${s.index}"
	private fun partKey(s: SegmentResult, partPosition: Int) = "s${s.index}.p$partPosition"

	private fun usableTimed(s: SegmentResult) = s.status == SegmentStatus.OK && s.text.isNotBlank() && s.endMs > s.startMs && s.startMs >= 0

	private fun timedKeyed(record: TranscriptRecord): List<Pair<String, SegmentResult>> =
		record.segments.sortedBy { it.startMs }.flatMap { s ->
			if (s.parts.isNotEmpty()) s.parts.sortedBy { it.startMs }.mapIndexed { i, p -> partKey(s, i) to p }
			else listOf(segmentKey(s) to s)
		}.filter { usableTimed(it.second) }

	private fun untimedKeyed(record: TranscriptRecord): List<Pair<String, String>> =
		record.text.lines().filter { it.isNotBlank() }.mapIndexed { i, t -> "l$i" to t }
}
