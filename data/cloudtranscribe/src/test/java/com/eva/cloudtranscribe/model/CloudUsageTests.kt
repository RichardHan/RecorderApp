package com.eva.cloudtranscribe.model

import com.eva.cloudtranscribe.usage.ModelPrice
import com.eva.cloudtranscribe.usage.TokenUsageEvent
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

	@Test
	fun title_requests_are_counted_in_the_same_request_total() {
		val usage = CloudUsage.summarize(
			transcripts = listOf(t(novStart, requests = 3, segments = listOf(seg(0, 60_000, 1)))),
			summaries = listOf(s(now)),
			zone = taipei,
			nowMillis = now,
			titleRequestAtMillis = listOf(novStart, now, octEnd),
		)
		assertEquals(MonthUsage(YearMonth.of(2026, 11), 1, 60_000, 5, 1), usage[0])
		assertEquals(MonthUsage(YearMonth.of(2026, 10), 0, 0, 1, 0), usage[1])
	}

	@Test
	fun token_events_fill_the_month_and_hide_empty_months() {
		val events = listOf(
			TokenUsageEvent(novStart, "gemini-3.8-flash", 1_000_000, 200_000, recordingId = 8),
			TokenUsageEvent(octEnd, "gemini-3.8-flash", 500_000, 0, recordingId = 7),
		)
		val prices = listOf(ModelPrice("gemini-3.8-flash", "1", "2"))
		val usage = CloudUsage.summarize(
			transcripts = emptyList(),
			summaries = emptyList(),
			zone = taipei,
			nowMillis = now,
			tokenEvents = events,
			prices = prices,
		)
		assertEquals(1_000_000L, usage[0].promptTokens)
		assertEquals(200_000L, usage[0].completionTokens)
		assertEquals(1.4, usage[0].cost.amount!!, 1e-9)
		assertEquals(500_000L, usage[1].promptTokens)
		val visible = CloudUsage.visibleMonths(usage)
		assertEquals(listOf(usage[0], usage[1]), visible)
	}

	@Test
	fun last_30_days_and_per_recording_use_the_token_log() {
		val events = listOf(
			TokenUsageEvent(now - 2L * 86_400_000L, "m", 100, 10, recordingId = 1),
			TokenUsageEvent(now - 40L * 86_400_000L, "m", 999, 9, recordingId = 2),
			TokenUsageEvent(now - 1_000L, "m", 50, 5, recordingId = 1),
		)
		val last30 = CloudUsage.lastDays(events, emptyList(), now)
		assertEquals(150L, last30.promptTokens)
		assertEquals(15L, last30.completionTokens)
		assertEquals(2, last30.tokenRequests)
		val rec = CloudUsage.forRecording(1L, events, emptyList())
		assertEquals(150L, rec.promptTokens)
		assertEquals(1L, rec.recordingId)
	}
}
