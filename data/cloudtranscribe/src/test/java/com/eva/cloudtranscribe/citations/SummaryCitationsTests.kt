package com.eva.cloudtranscribe.citations

import com.eva.cloudtranscribe.engine.Prompts
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SegmentStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryCitationsTests {

	private val prov = Provenance(engine = "cloud", model = "m")
	private fun seg(i: Int, start: Long, end: Long, text: String, status: SegmentStatus = SegmentStatus.OK, parts: List<SegmentResult> = emptyList()) =
		SegmentResult(index = i, startMs = start, endMs = end, status = status, text = text, parts = parts)
	private fun record(source: TranscriptSource = TranscriptSource.CLOUD, segments: List<SegmentResult>) =
		TranscriptRecord(id = "t", recordingId = 1, source = source, provenance = prov, createdAtMillis = 0, status = RunStatus.SUCCESS, segments = segments)

	private val segs = listOf(
		CiteSegment(1, 0, 30_000, "今天討論 Q3 報價，預算上限五十萬"),
		CiteSegment(2, 30_000, 60_000, "Amy 負責下週寄出合約草稿"),
		CiteSegment(3, 192_000, 220_000, "我們然後就是這個"),
	)

	@Test
	fun parses_single_and_multiple_markers_and_strips_them() {
		val r = SummaryCitations.parse("- Q3 報價預算上限五十萬〔S1〕\n- Amy 下週寄合約草稿〔S1,S2〕", segs)
		assertEquals("- Q3 報價預算上限五十萬\n- Amy 下週寄合約草稿", r.text)
		assertEquals(listOf(Citation(0, 1, 0, 30_000), Citation(1, 2, 30_000, 60_000)), r.citations)
	}

	@Test
	fun unknown_numbers_are_rejected_but_text_is_kept() {
		val r = SummaryCitations.parse("- 報價〔S9〕", segs)
		assertEquals("- 報價", r.text)
		assertTrue(r.citations.isEmpty())
	}

	@Test
	fun content_mismatch_and_filler_only_overlap_are_rejected() {
		assertTrue(SummaryCitations.parse("- 合約草稿由 Amy 寄出〔S1〕", segs).citations.isEmpty())
		assertTrue(SummaryCitations.parse("- 我們就是這樣〔S3〕", segs).citations.isEmpty())
		assertFalse(SummaryCitations.sharesWord("我們", "我們開始"))
		assertTrue(SummaryCitations.sharesWord("合約草稿", "寄出合約"))
		assertTrue(SummaryCitations.sharesWord("send the Q3 deck", "Q3 numbers"))
	}

	@Test
	fun lines_without_markers_have_no_time() {
		val r = SummaryCitations.parse("目前無重點", segs)
		assertEquals("目前無重點", r.text)
		assertTrue(r.citations.isEmpty())
	}

	@Test
	fun only_ok_cloud_segments_are_citable_and_parts_use_their_own_times() {
		val t = record(segments = listOf(
			seg(1, 300_000, 600_000, "", SegmentStatus.CONTENT_FILTERED, parts = listOf(seg(0, 300_000, 450_000, "後段一"), seg(1, 450_000, 600_000, "x", SegmentStatus.FAILED))),
			seg(0, 0, 300_000, "前段"),
			seg(2, 600_000, 900_000, "失敗", SegmentStatus.FAILED),
			seg(3, 900_000, 1_200_000, "", SegmentStatus.SILENT),
		))
		val s = SummaryCitations.segmentsOf(t)!!
		assertEquals(listOf("前段" to 0L, "後段一" to 300_000L), s.map { it.text to it.startMs })
		assertEquals(listOf(1, 2), s.map { it.number })
		assertNull(SummaryCitations.segmentsOf(record(TranscriptSource.OFFLINE, listOf(seg(0, 0, 10, "a")))))
		assertNull(SummaryCitations.segmentsOf(record(segments = emptyList())))
	}

	@Test
	fun prompt_keeps_format_rules_and_inserts_instruction_before_numbered_transcript() {
		val numbered = SummaryCitations.numberedTranscript(segs.take(1))
		assertEquals("[S1 00:00–00:30] 今天討論 Q3 報價，預算上限五十萬", numbered)
		val base = Prompts.summary(numbered)
		val p = SummaryCitations.withInstruction(base)
		assertTrue(p.startsWith(Prompts.summary("x").substringBefore("\n\n逐字稿：")))
		assertTrue(p.indexOf(SummaryCitations.INSTRUCTION) < p.indexOf("逐字稿：\n[S1"))
		assertTrue(p.endsWith(numbered))
	}

	@Test
	fun legacy_summary_json_and_settings_load_with_citations_off() {
		val json = Json { ignoreUnknownKeys = true }
		val legacy = """{"id":"s","recordingId":1,"sourceTranscriptId":"t","provenance":{"engine":"cloud-summary","model":"m"},"createdAtMillis":1,"status":"SUCCESS","text":"- a"}"""
		val r = json.decodeFromString(SummaryRecord.serializer(), legacy)
		assertTrue(r.citations.isEmpty())
		assertNull(r.provenance.citations)
		assertFalse(json.decodeFromString(CloudSettings.serializer(), "{}").summaryCitations)
	}

	@Test
	fun clock_formats_hours() {
		assertEquals("03:12", SummaryCitations.clock(192_000))
		assertEquals("1:00:05", SummaryCitations.clock(3_605_000))
	}
}
