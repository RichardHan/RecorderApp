package com.eva.cloudtranscribe.model

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExportFormatterTests {

	private fun record(
		segments: List<SegmentResult>,
		status: RunStatus = RunStatus.SUCCESS,
		source: TranscriptSource = TranscriptSource.OFFLINE,
		text: String = segments.joinToString(" ") { it.text },
	) = TranscriptRecord("t1", 7, source, Provenance("offline", model = "sherpa"), 0, status = status, text = text, segments = segments)

	private fun seg(i: Int, start: Long, end: Long, text: String, status: SegmentStatus = SegmentStatus.OK) =
		SegmentResult(i, start, end, status, text)

	private val sentences = record(
		listOf(
			seg(1, 4_200, 7_000, "第二句"),
			seg(0, 0, 4_500, "第一句"),
			seg(2, 7_000, 9_000, "", SegmentStatus.SILENT),
		),
	)

	@Test
	fun srt_uses_real_sentence_timing_sorted_without_overlap() {
		assertEquals(
			"1\n00:00:00,000 --> 00:00:04,200\n第一句\n\n2\n00:00:04,200 --> 00:00:07,000\n第二句\n\n",
			TranscriptExportFormatter.render(TranscriptExportFormat.SRT, sentences),
		)
		assertEquals(TranscriptExportFormat.entries.toList(), TranscriptExportFormatter.availableFormats(sentences))
	}

	@Test
	fun srt_is_refused_for_chunk_level_missing_or_unfinished_timing() {
		val chunks = record(listOf(seg(0, 0, 300_000, "整段五分鐘")), source = TranscriptSource.CLOUD)
		val noTiming = record(emptyList(), text = "只有全文")
		val broken = record(listOf(seg(0, -1, 2_000, "a")))
		val running = record(listOf(seg(0, 0, 2_000, "a")), status = RunStatus.RUNNING)
		listOf(chunks, noTiming, broken, running).forEach { r ->
			assertNull(TranscriptExportFormatter.srtCues(r))
			assertFalse(TranscriptExportFormat.SRT in TranscriptExportFormatter.availableFormats(r))
		}
		val error = runCatching { TranscriptExportFormatter.render(TranscriptExportFormat.SRT, chunks) }.exceptionOrNull()
		assertTrue(error is IllegalArgumentException)
	}

	@Test
	fun markdown_golden_with_summary_and_bookmarks() {
		val extras = TranscriptExportExtras(
			title = "週會_v2.m4a",
			recordedAt = LocalDateTime.of(2026, 11, 14, 9, 30),
			summary = "- 決定 A\n- B 負責報價",
			summaryModel = "gpt-x",
			bookmarks = listOf(ExportBookmark(65_000, "報價"), ExportBookmark(3_000, "")),
		)
		val expected = """
			# 週會\_v2.m4a

			- 錄音時間：2026-11-14 09:30
			- 來源：離線｜模型：sherpa
			- 狀態：SUCCESS

			## 重點／摘要（模型：gpt-x）

			- 決定 A
			- B 負責報價

			## 書籤

			- [00:03] （無文字）
			- [01:05] 報價

			## 逐字稿

			**[00:00–00:04]** 第一句

			**[00:04–00:07]** 第二句

			**[00:07–00:09]** _來源標為靜音_
		""".trimIndent() + "\n"
		assertEquals(expected, TranscriptExportFormatter.render(TranscriptExportFormat.MARKDOWN, sentences, extras))
	}

	@Test
	fun txt_adds_optional_sections_before_the_existing_transcript_text() {
		val plain = TranscriptExportFormatter.render(TranscriptExportFormat.TXT, sentences)
		assertEquals(TranscriptTextExport.render(sentences), plain)

		val withExtras = TranscriptExportFormatter.render(
			TranscriptExportFormat.TXT, sentences,
			TranscriptExportExtras(title = "週會", summary = "決定 A", bookmarks = listOf(ExportBookmark(3_723_000, "長會議"))),
		)
		assertTrue(withExtras.startsWith("標題：週會\n\n【重點／摘要】\n決定 A\n\n【書籤】\n- [1:02:03] 長會議\n"))
		assertTrue(withExtras.endsWith(TranscriptTextExport.render(sentences)))
	}

	@Test
	fun unfinished_transcripts_are_marked() {
		val running = record(listOf(seg(0, 0, 2_000, "a")), status = RunStatus.RUNNING)
		assertTrue(TranscriptExportFormatter.render(TranscriptExportFormat.MARKDOWN, running).contains("轉錄未完成"))
	}

	@Test
	fun empty_transcripts_have_nothing_to_export() {
		assertFalse(TranscriptExportFormatter.hasExportableText(record(emptyList(), text = " ")))
		assertFalse(TranscriptExportFormatter.hasExportableText(record(listOf(seg(0, 0, 1_000, "", SegmentStatus.SILENT)), text = "")))
		assertTrue(TranscriptExportFormatter.hasExportableText(sentences))
	}

	@Test
	fun file_names_are_sanitized() {
		val at = LocalDateTime.of(2026, 11, 14, 9, 5)
		assertEquals("週會_ A_B_20261114-0905.md", TranscriptExportFormatter.fileName("週會/ A:B?.m4a", at, 7, TranscriptExportFormat.MARKDOWN))
		assertEquals("transcript-7.srt", TranscriptExportFormatter.fileName("  ...  ", null, 7, TranscriptExportFormat.SRT))
		assertEquals("transcript-7_20261114-0905.txt", TranscriptExportFormatter.fileName(null, at, 7, TranscriptExportFormat.TXT))
		assertTrue(TranscriptExportFormatter.sanitize("x".repeat(200)).length <= 80)
	}
}
