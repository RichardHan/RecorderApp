package com.eva.cloudtranscribe.model

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TranscriptExportFormat(val extension: String, val mimeType: String, val label: String) {
	TXT("txt", "text/plain", "TXT"),
	MARKDOWN("md", "text/markdown", "Markdown"),
	SRT("srt", "application/x-subrip", "SRT 字幕"),
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
		extras.title?.takeIf { it.isNotBlank() }?.let { appendLine("標題：${it.trim()}") }
		extras.recordedAt?.let { appendLine("錄音時間：${DATE.format(it)}") }
		extras.summary?.takeIf { it.isNotBlank() }?.let { summary ->
			appendLine()
			appendLine("【重點／摘要${extras.summaryModel?.let { "（模型：$it）" }.orEmpty()}】")
			appendLine(summary.trim())
		}
		if (extras.bookmarks.isNotEmpty()) {
			appendLine()
			appendLine("【書籤】")
			extras.bookmarks.sortedBy { it.atMs }.forEach { appendLine("- [${clock(it.atMs)}] ${it.text.ifBlank { "（無文字）" }}") }
		}
		if (isNotEmpty()) appendLine("\n----------------------------------------\n")
		append(TranscriptTextExport.render(record))
	}

	private fun markdown(record: TranscriptRecord, extras: TranscriptExportExtras): String = buildString {
		appendLine("# ${extras.title?.trim()?.takeIf { it.isNotEmpty() }?.let(::escapeMd) ?: "RecorderApp 逐字稿"}")
		appendLine()
		extras.recordedAt?.let { appendLine("- 錄音時間：${DATE.format(it)}") }
		appendLine("- 來源：${if (record.source == TranscriptSource.OFFLINE) "離線" else "雲端"}｜模型：${escapeMd(record.provenance.model)}")
		appendLine("- 狀態：${record.status.name}")
		if (record.status != RunStatus.SUCCESS) appendLine("\n> ⚠ 轉錄未完成或非完整成功，可能有遺漏。")
		record.warnings.forEach { appendLine("\n> ⚠ ${escapeMd(it)}") }
		extras.summary?.takeIf { it.isNotBlank() }?.let { summary ->
			appendLine()
			appendLine("## 重點／摘要${extras.summaryModel?.let { "（模型：${escapeMd(it)}）" }.orEmpty()}")
			appendLine()
			appendLine(summary.trim())
		}
		if (extras.bookmarks.isNotEmpty()) {
			appendLine()
			appendLine("## 書籤")
			appendLine()
			extras.bookmarks.sortedBy { it.atMs }.forEach { appendLine("- [${clock(it.atMs)}] ${escapeMd(it.text.ifBlank { "（無文字）" })}") }
		}
		appendLine()
		appendLine("## 逐字稿")
		appendLine()
		val segments = flatten(record)
		if (segments.isEmpty()) {
			appendLine("_來源未提供分段時間_")
			appendLine()
			appendLine(record.text.trim())
		} else segments.forEach { segment ->
			val time = if (segment.startMs >= 0 && segment.endMs > segment.startMs) "${clock(segment.startMs)}–${clock(segment.endMs)}" else "時間未核實"
			val text = when (segment.status) {
				SegmentStatus.OK -> segment.text.trim().ifBlank { "_來源文字空白_" }
				SegmentStatus.SILENT -> "_來源標為靜音_"
				else -> "_未完成／不可採用的片段：${segment.status.name}_"
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
