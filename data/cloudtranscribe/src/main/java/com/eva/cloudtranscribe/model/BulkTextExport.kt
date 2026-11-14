package com.eva.cloudtranscribe.model

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One recording in a bulk text export; texts are null when the recording has none. */
data class BulkExportEntry(
	val recordingId: Long,
	val title: String,
	val recordedAt: String,
	val durationMs: Long,
	val transcript: String?,
	val summary: String?,
)

/**
 * Bulk text export (spec: market-bulk-export): index.csv plus one folder per recording that has text. No audio,
 * keys or settings are ever included; the caller writes to a location the user picked.
 */
object BulkTextExport {

	const val MAX_FOLDER_TITLE = 40
	private val ILLEGAL = Regex("""[\\/:*?"<>|\r\n\t]""")

	/** The transcript the player would show, rendered with the existing single-recording format. */
	fun transcriptText(records: List<TranscriptRecord>): String? =
		PlaybackTranscripts.defaultSelection(records)?.let(TranscriptTextExport::render)

	fun summaryText(summaries: List<SummaryRecord>): String? =
		summaries.sortedByDescending { it.createdAtMillis }.firstOrNull { it.status == RunStatus.SUCCESS && it.text.isNotBlank() }?.text

	fun folderName(entry: BulkExportEntry): String {
		val clean = ILLEGAL.replace(entry.title, " ").replace(Regex("""\s+"""), " ").trim().take(MAX_FOLDER_TITLE).trim()
		return if (clean.isEmpty()) "${entry.recordingId}" else "${entry.recordingId}_$clean"
	}

	fun csvField(value: String): String =
		if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

	fun indexCsv(entries: List<BulkExportEntry>): String = buildString {
		append("recording_id,title,recorded_at,duration_seconds,has_transcript,has_summary\n")
		entries.forEach { e ->
			append(listOf(e.recordingId.toString(), e.title, e.recordedAt, (e.durationMs / 1000).toString(),
				(e.transcript != null).toString(), (e.summary != null).toString()).joinToString(",") { csvField(it) }).append('\n')
		}
	}

	/** Writes the ZIP and returns how many recordings got a folder. The stream is closed. */
	fun write(entries: List<BulkExportEntry>, out: OutputStream): Int {
		var folders = 0
		ZipOutputStream(out.buffered()).use { zip ->
			fun put(name: String, text: String) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(text.toByteArray(Charsets.UTF_8))
				zip.closeEntry()
			}
			put("index.csv", "\uFEFF" + indexCsv(entries)) // BOM so spreadsheet apps read CJK correctly
			entries.forEach { e ->
				if (e.transcript == null && e.summary == null) return@forEach
				val dir = folderName(e)
				e.transcript?.let { put("$dir/transcript.txt", it) }
				e.summary?.let { put("$dir/summary.md", it) }
				folders++
			}
		}
		return folders
	}
}
