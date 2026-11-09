package com.eva.cloudtranscribe.model

import java.time.Instant
import java.util.Locale

/** Plain text only: full original content, real timing, and explicit evidence gaps. */
object TranscriptTextExport {
	fun render(record: TranscriptRecord): String = buildString {
		appendLine("RecorderApp 逐字稿")
		appendLine("錄音：${record.recordingId}")
		appendLine("來源：${if (record.source == TranscriptSource.OFFLINE) "離線" else "雲端"}｜模型：${record.provenance.model}")
		appendLine("狀態：${record.status.name}")
		appendLine("最後更新（UTC）：${Instant.ofEpochMilli(record.updatedAtMillis)}")
		if (record.status != RunStatus.SUCCESS) appendLine("⚠ 非完整成功逐字稿，可能有遺漏或尚未完成的內容。")
		record.warnings.forEach { appendLine("⚠ $it") }
		appendLine()
		if (record.segments.isEmpty()) {
			appendLine("[來源未提供分段時間]")
			append(record.text)
		} else {
			val segments = record.segments.sortedBy { it.startMs }.flatMap {
				if (it.parts.isEmpty()) listOf(it) else it.parts.sortedBy { part -> part.startMs }
			}
			segments.forEach { segment ->
				val time = if (segment.startMs >= 0 && segment.endMs > segment.startMs)
					"${timestamp(segment.startMs)}–${timestamp(segment.endMs)}" else "時間未核實"
				appendLine("[$time]")
				when (segment.status) {
					SegmentStatus.OK -> appendLine(segment.text.ifBlank { "[來源文字空白]" })
					SegmentStatus.SILENT -> appendLine("[來源標為靜音]")
					else -> appendLine("[未完成／不可採用的片段：${segment.status.name}]")
				}
				appendLine()
			}
		}
	}

	private fun timestamp(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
}
