package com.eva.cloudtranscribe.model

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings

import java.time.Instant
import java.util.Locale

/** Plain text only: full original content, real timing, and explicit evidence gaps. */
object TranscriptTextExport {
	fun render(record: TranscriptRecord): String = buildString {
		appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_recorderapp_transcript, "RecorderApp 逐字稿"))
		appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_recording, "錄音：%1\$s", record.recordingId))
		appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_source_model_alt, "來源：%1\$s｜模型：%2\$s", if (record.source == TranscriptSource.OFFLINE) UiStrings.get(StringsRes.string.data_cloudtranscribe_offline, "離線") else UiStrings.get(StringsRes.string.data_cloudtranscribe_cloud, "雲端"), record.provenance.model))
		appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_status_alt, "狀態：%1\$s", record.status.name))
		appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_last_updated_utc, "最後更新（UTC）：%1\$s", Instant.ofEpochMilli(record.updatedAtMillis)))
		if (record.status != RunStatus.SUCCESS) appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_transcription_was_incomplete_and_may_contain_gaps, "⚠ 非完整成功逐字稿，可能有遺漏或尚未完成的內容。"))
		record.warnings.forEach { appendLine("⚠ $it") }
		appendLine()
		if (record.segments.isEmpty()) {
			appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_no_segment_timestamps_available_alt, "[來源未提供分段時間]"))
			append(record.text)
		} else {
			val segments = record.segments.sortedBy { it.startMs }.flatMap {
				if (it.parts.isEmpty()) listOf(it) else it.parts.sortedBy { part -> part.startMs }
			}
			segments.forEach { segment ->
				val time = if (segment.startMs >= 0 && segment.endMs > segment.startMs)
					"${timestamp(segment.startMs)}–${timestamp(segment.endMs)}" else UiStrings.get(StringsRes.string.data_cloudtranscribe_time_unverified, "時間未核實")
				appendLine("[$time]")
				when (segment.status) {
					SegmentStatus.OK -> appendLine(segment.text.ifBlank { UiStrings.get(StringsRes.string.data_cloudtranscribe_empty_source_text_alt, "[來源文字空白]") })
					SegmentStatus.SILENT -> appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_marked_as_silence_alt, "[來源標為靜音]"))
					else -> appendLine(UiStrings.get(StringsRes.string.data_cloudtranscribe_incomplete_or_unusable_segment_alt, "[未完成／不可採用的片段：%1\$s]", segment.status.name))
				}
				appendLine()
			}
		}
	}

	private fun timestamp(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
}
