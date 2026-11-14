package com.eva.cloudtranscribe.model

import org.junit.Assert.*
import org.junit.Test

class TranscriptTextExportTests {
	private fun record() = TranscriptRecord("t", 1, TranscriptSource.OFFLINE,
		Provenance("offline", model = "local-model"), 0, status = RunStatus.SUCCESS, text = "Meeting text")
	@Test fun `exports whole text without preview truncation or fake timing`() {
		val text = "完整文字".repeat(2000)
		val result = TranscriptTextExport.render(record().copy(text = text))
		assertTrue(result.endsWith(text))
		assertTrue(result.contains("來源未提供分段時間"))
	}
	@Test fun `includes provenance and explicit UTC date`() {
		val result = TranscriptTextExport.render(record())
		assertTrue(result.contains("離線｜模型：local-model"))
		assertTrue(result.contains("1970-01-01T00:00:00Z"))
	}
	@Test fun `preserves actual millisecond times and silence gaps`() {
		val r = record().copy(segments = listOf(SegmentResult(0, 1001, 2305, SegmentStatus.OK, "one"), SegmentResult(1, 9000, 10000, SegmentStatus.OK, "two")))
		val result = TranscriptTextExport.render(r)
		assertTrue(result.contains("00:00:01.001–00:00:02.305"))
		assertTrue(result.contains("00:00:09.000–00:00:10.000"))
	}
	@Test fun `does not promote failed text to transcript evidence`() {
		val r = record().copy(status = RunStatus.PARTIAL, segments = listOf(SegmentResult(0, 0, 1000, SegmentStatus.FAILED, "untrusted output")))
		val result = TranscriptTextExport.render(r)
		assertFalse(result.contains("untrusted output"))
		assertTrue(result.contains("非完整成功"))
		assertTrue(result.contains("FAILED"))
	}
	@Test fun `child segments export once without duplicate parent aggregate`() {
		val child = SegmentResult(0, 1000, 2000, SegmentStatus.OK, "child")
		val r = record().copy(segments = listOf(SegmentResult(0, 0, 2000, SegmentStatus.OK, "aggregate", parts = listOf(child))))
		val result = TranscriptTextExport.render(r)
		assertTrue(result.contains("child"))
		assertFalse(result.contains("aggregate"))
	}
	@Test fun `invalid time is marked unverified and warnings retained`() {
		val r = record().copy(warnings = listOf("missing audio"), segments = listOf(SegmentResult(0, -1, 0, SegmentStatus.OK, "text")))
		val result = TranscriptTextExport.render(r)
		assertTrue(result.contains("時間未核實"))
		assertTrue(result.contains("missing audio"))
	}
}
