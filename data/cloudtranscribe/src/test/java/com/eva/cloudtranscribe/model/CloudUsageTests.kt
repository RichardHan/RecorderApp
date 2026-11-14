package com.eva.cloudtranscribe.model

import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudUsageTests {

	private val taipei = ZoneId.of("Asia/Taipei")
	private val prov = Provenance(engine = "cloud", model = "m")
	// 2026-11-15 10:00 Asia/Taipei
	private val now = 1_794_708_000_000L
	// 2026-10-31 23:30 and 2026-11-01 00:30 Asia/Taipei
	private val octEnd = 1_793_460_600_000L
	private val novStart = octEnd + 3_600_000L

	private fun seg(start: Long, end: Long, attempts: Int, parts: List<SegmentResult> = emptyList()) =
		SegmentResult(0, start, end, SegmentStatus.OK, "x", attempts = attempts, parts = parts)

	private fun t(at: Long, source: TranscriptSource = TranscriptSource.CLOUD, requests: Int = 1, segments: List<SegmentResult> = emptyList()) =
		TranscriptRecord("t$at", 1, source, prov, at, status = RunStatus.SUCCESS, segments = segments, requestCount = requests)

	private fun s(at: Long, status: RunStatus = RunStatus.SUCCESS) = SummaryRecord("s$at", 1, "t", prov, at, status)

	@Test
	fun months_use_local_time_zone() {
		assertEquals(YearMonth.of(2026, 10), CloudUsage.monthOf(octEnd, taipei))
		assertEquals(YearMonth.of(2026, 11), CloudUsage.monthOf(novStart, taipei))
	}

	@Test
	fun only_sent_pieces_count_and_parts_replace_parent() {
		val r = t(now, segments = listOf(
			seg(0, 60_000, attempts = 1),
			seg(60_000, 120_000, attempts = 0),
			seg(120_000, 420_000, attempts = 1, parts = listOf(seg(120_000, 270_000, 1), seg(270_000, 420_000, 0))),
		))
		assertEquals(60_000L + 150_000L, CloudUsage.uploadedMs(r))
	}

	@Test
	fun summarize_counts_cloud_only_and_includes_failed_summaries() {
		val usage = CloudUsage.summarize(
			transcripts = listOf(t(novStart, requests = 3, segments = listOf(seg(0, 60_000, 1))), t(octEnd, requests = 2), t(now, TranscriptSource.OFFLINE, requests = 9)),
			summaries = listOf(s(now), s(now, RunStatus.FAILED), s(octEnd)),
			zone = taipei, nowMillis = now,
		)
		assertEquals(CloudUsage.MONTHS, usage.size)
		assertEquals(YearMonth.of(2026, 11), usage[0].month)
		assertEquals(MonthUsage(YearMonth.of(2026, 11), 1, 60_000, 3, 2), usage[0])
		assertEquals(MonthUsage(YearMonth.of(2026, 10), 1, 0, 2, 1), usage[1])
		assertEquals(YearMonth.of(2026, 6), usage.last().month)
	}

	@Test
	fun records_older_than_the_window_are_ignored() {
		val old = now - 400L * 86_400_000L
		val usage = CloudUsage.summarize(listOf(t(old)), listOf(s(old)), taipei, now)
		assertEquals(0, usage.sumOf { it.cloudTranscripts + it.summaries })
		assertEquals("1.5", CloudUsage.minutes(90_000))
	}
}
