package com.eva.cloudtranscribe.model

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TranscriptExportFormat(val extension: String, val mimeType: String, val label: String) {
	TXT("txt", "text/plain", "TXT"),
	MARKDOWN("md", "text/markdown", "Markdown"),
	SRT("srt", "application/x-subrip", UiStrings.get(StringsRes.string.data_cloudtranscribe_srt_subtitles, "SRT 字幕")),
}

data class ExportBookmark(val atMs: Long, val text: String)

/** Optional context around a transcript; everything here is already on the phone. */
data class TranscriptExportExtras(
	val title: String? = null,
	val recordedAt: LocalDateTime? = null,
	/** Saved summary text, exported as-is (no new model call). */
	val summary: String? = null,
	val summaryModel: String? = null,
	val bookmarks: List<ExportBookmark> = emptyList(),
)

data class SrtCue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

/** Local-only TXT / Markdown / SRT rendering. SRT only exists when the transcript kept real sentence timing. */
object TranscriptExportFormatter {

	/** A cue longer than this is a transcription chunk, not a sentence; chunk-level timing is never turned into subtitles. */
	const val MAX_SRT_CUE_MS = 30_000L

	fun availableFormats(record: TranscriptRecord): List<TranscriptExportFormat> =
		if (srtCues(record) != null) TranscriptExportFormat.entries.toList()
		else listOf(TranscriptExportFormat.TXT, TranscriptExportFormat.MARKDOWN)

	fun hasExportableText(record: TranscriptRecord): Boolean =
		record.text.isNotBlank() || flatten(record).any { it.status == SegmentStatus.OK && it.text.isNotBlank() }

	/** Real sentence-level cues, or null (never estimated, never evenly split). */
	fun srtCues(record: TranscriptRecord): List<SrtCue>? {
		if (record.status != RunStatus.SUCCESS) return null
		val all = flatten(record)
		if (all.isEmpty()) return null
		val spoken = all.filter { it.status == SegmentStatus.OK && it.text.isNotBlank() }
		if (spoken.isEmpty()) return null
		if (spoken.any { it.startMs < 0 || it.endMs <= it.startMs || it.endMs - it.startMs > MAX_SRT_CUE_MS }) return null
		val sorted = spoken.sortedBy { it.startMs }
		return sorted.mapIndexed { i, segment ->
			val next = sorted.getOrNull(i + 1)?.startMs
			// no overlapping cues: end at the next real start when ranges overlap
			val end = if (next != null && next > segment.startMs) minOf(segment.endMs, next) else segment.endMs
			SrtCue(i + 1, segment.startMs, end, segment.text.trim())
		}
	}

	fun render(format: TranscriptExportFormat, record: TranscriptRecord, extras: TranscriptExportExtras = TranscriptExportExtras()): String =
		when (format) {
			TranscriptExportFormat.TXT -> txt(record, extras)
			TranscriptExportFormat.MARKDOWN -> markdown(record, extras)
			TranscriptExportFormat.SRT -> srt(srtCues(record) ?: throw IllegalArgumentException("No real sentence timing; SRT is not available"))
		}

	private fun txt(record: TranscriptRecord, extras: TranscriptExportExtras): String = buildString {
		extras.title?.takeIf { it.isNotBlank() }?.let { appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_title, "標題：%1\$s", it.trim())) }
		extras.recordedAt?.let { appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_recorded_alt, "錄音時間：%1\$s", DATE.format(it))) }
		extras.summary?.takeIf { it.isNotBlank() }?.let { summary ->
			appendLine()
			appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_highlights_summary_alt, "【重點／摘要%1\$s】", extras.summaryModel?.let { UiStrings.get(StringsRes.string.data_cloudtranscribe_model, "（模型：%1\$s）", it) }.orEmpty()))
			appendLine(summary.trim())
		}
		if (extras.bookmarks.isNotEmpty()) {
			appendLine()
			appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_bookmarks_alt, "【書籤】"))
			extras.bookmarks.sortedBy { it.atMs }.forEach { appendLine("- [${clock(it.atMs)}] ${it.text.ifBlank { UiStrings.get(StringsRes.string.data_cloudtranscribe_no_text, "（無文字）") }}") }
		}
		if (isNotEmpty()) appendLine("\n----------------------------------------\n")
		append(TranscriptTextExport.render(record))
	}

	private fun markdown(record: TranscriptRecord, extras: TranscriptExportExtras): String = buildString {
		appendLine("# ${extras.title?.trim()?.takeIf { it.isNotEmpty() }?.let(::escapeMd) ?: UiStrings.get(StringsRes.string.data_cloudtranscribe_recorderapp_transcript, "RecorderApp 逐字稿")}")
		appendLine()
		extras.recordedAt?.let { appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_recorded, "- 錄音時間：%1\$s", DATE.format(it))) }
		appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_source_model, "- 來源：%1\$s｜模型：%2\$s", if (record.source == TranscriptSource.OFFLINE) UiStrings.get(StringsRes.string.data_cloudtranscribe_offline, "離線") else UiStrings.get(StringsRes.string.data_cloudtranscribe_cloud, "雲端"), escapeMd(record.provenance.model)))
		appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_status, "- 狀態：%1\$s", record.status.name))
		if (record.status != RunStatus.SUCCESS) appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_n_transcription_was_incomplete_and_may_contain, "\n> ⚠ 轉錄未完成或非完整成功，可能有遺漏。"))
		record.warnings.forEach { appendLine("\n> ⚠ ${escapeMd(it)}") }
		extras.summary?.takeIf { it.isNotBlank() }?.let { summary ->
			appendLine()
			appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_highlights_summary, "## 重點／摘要%1\$s", extras.summaryModel?.let { UiStrings.get(StringsRes.string.data_cloudtranscribe_model, "（模型：%1\$s）", escapeMd(it)) }.orEmpty()))
			appendLine()
			appendLine(summary.trim())
		}
		if (extras.bookmarks.isNotEmpty()) {
			appendLine()
			appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_bookmarks, "## 書籤"))
			appendLine()
			extras.bookmarks.sortedBy { it.atMs }.forEach { appendLine("- [${clock(it.atMs)}] ${escapeMd(it.text.ifBlank { UiStrings.get(StringsRes.string.data_cloudtranscribe_no_text, "（無文字）") })}") }
		}
		appendLine()
		appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_transcript, "## 逐字稿"))
		appendLine()
		val segments = flatten(record)
		if (segments.isEmpty()) {
			appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_no_segment_timestamps_available, "_來源未提供分段時間_"))
			appendLine()
			appendLine(record.text.trim())
		} else segments.forEach { segment ->
			val time = if (segment.startMs >= 0 && segment.endMs > segment.startMs) "${clock(segment.startMs)}–${clock(segment.endMs)}" else UiStrings.get(StringsRes.string.data_cloudtranscribe_time_unverified, "時間未核實")
			val text = when (segment.status) {
				SegmentStatus.OK -> segment.text.trim().ifBlank { UiStrings.get(StringsRes.string.data_cloudtranscribe_empty_source_text, "_來源文字空白_") }
				SegmentStatus.SILENT -> UiStrings.get(StringsRes.string.data_cloudtranscribe_marked_as_silence, "_來源標為靜音_")
				else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_incomplete_or_unusable_segment, "_未完成／不可採用的片段：%1\$s_", segment.status.name)
			}
			appendLine("**[$time]** $text")
			appendLine()
		}
	}.trimEnd() + "\n"

	private fun srt(cues: List<SrtCue>): String = buildString {
		cues.forEach { cue ->
			appendLine(cue.index)
			appendLine("${srtTime(cue.startMs)} --> ${srtTime(cue.endMs)}")
			appendLine(cue.text)
			appendLine()
		}
	}

	/** `<錄音名>_<yyyyMMdd-HHmm>.<ext>`, illegal file-name characters removed. */
	fun fileName(title: String?, recordedAt: LocalDateTime?, recordingId: Long, format: TranscriptExportFormat): String {
		val base = title?.substringBeforeLast('.')?.let(::sanitize)?.takeIf { it.isNotEmpty() } ?: "transcript-$recordingId"
		val stamp = recordedAt?.let { "_" + FILE_STAMP.format(it) }.orEmpty()
		return "$base$stamp.${format.extension}"
	}

	fun sanitize(name: String): String = name
		.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
		.replace(Regex("\\s+"), " ")
		.trim(' ', '.', '_')
		.take(80)

	private fun flatten(record: TranscriptRecord): List<SegmentResult> = record.segments.sortedBy { it.startMs }.flatMap {
		if (it.parts.isEmpty()) listOf(it) else it.parts.sortedBy { part -> part.startMs }
	}

	private fun escapeMd(text: String) = text.replace(Regex("([\\\\`*_\\[\\]#])"), "\\\\$1")

	fun clock(ms: Long): String {
		val total = ms.coerceAtLeast(0) / 1000
		val h = total / 3600
		return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, (total / 60) % 60, total % 60)
		else String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
	}

	fun srtTime(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)

	private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
	private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)
}
