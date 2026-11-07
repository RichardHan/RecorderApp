package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.live.MergedLine.Kind as K
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.live.BoundedChunkQueue
import com.eva.cloudtranscribe.live.CloudSpan
import com.eva.cloudtranscribe.live.LiveCloudSession
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.live.OfflineLine
import com.eva.cloudtranscribe.live.SessionGuard
import com.eva.cloudtranscribe.live.SummaryGate
import com.eva.cloudtranscribe.live.TranscriptMerge
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SegmentStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCloudTests {
	private val on = LiveCloudSettings(enabled = true, consentAcceptedAtMillis = 1L)

	// ---- settings defaults ----
	@Test fun defaultsAreOffAndMakeNoCalls() {
		val d = LiveCloudSettings()
		assertFalse(d.isActive)
		assertEquals(60_000L, d.transcribeIntervalMs)
		assertEquals(30_000L, d.summaryIntervalMs)
		assertEquals(90_000L, d.firstSummaryDelayMs)
		assertEquals("deepseek-v4.1-flash", d.summaryModel)
		assertFalse(d.summaryReuseTranscribeKey)
		assertFalse(SummaryGate.shouldRun(d, 999_000, null, 5, 10, 0, false))
	}

	@Test fun enabledWithoutConsentIsInactive() = assertFalse(LiveCloudSettings(enabled = true).isActive)

	@Test fun promptBlankResetsToDefaultAndIntervalsClamp() {
		val n = LiveCloudSettings(summaryPrompt = "  ", transcribeIntervalSec = 1, summaryIntervalSec = 99999).normalized()
		assertEquals(LiveCloudSettings.DEFAULT_SUMMARY_PROMPT, n.summaryPrompt)
		assertEquals(LiveCloudSettings.MIN_TRANSCRIBE_INTERVAL, n.transcribeIntervalSec)
		assertEquals(LiveCloudSettings.MAX_SUMMARY_INTERVAL, n.summaryIntervalSec)
		assertEquals("我的 prompt", LiveCloudSettings(summaryPrompt = "我的 prompt").normalized().summaryPrompt)
	}

	@Test fun summaryKeyNeverImplicitlyShared() {
		val s = on.copy(summaryBaseUrl = "https://x")
		assertEquals("尚未設定摘要 API Key", s.validateSummary(hasSummaryKey = false, hasTranscribeKey = true))
		assertNull(s.copy(summaryReuseTranscribeKey = true).validateSummary(false, true))
	}

	// ---- schedule gate ----
	@Test fun noSummaryBeforeFirstDelay() = assertFalse(SummaryGate.shouldRun(on, 89_999, null, 1, 3, 0, false))
	@Test fun noSummaryWithoutCloudChunk() = assertFalse(SummaryGate.shouldRun(on, 120_000, null, 0, 3, 0, false))
	@Test fun firstSummaryAt90sWithCloud() = assertTrue(SummaryGate.shouldRun(on, 90_000, null, 1, 1, 0, false))
	@Test fun summaryOnlyWithNewContent() = assertFalse(SummaryGate.shouldRun(on, 200_000, 100_000, 2, 4, 4, false))
	@Test fun summaryEvery30s() {
		assertFalse(SummaryGate.shouldRun(on, 119_000, 90_000, 2, 5, 4, false))
		assertTrue(SummaryGate.shouldRun(on, 120_000, 90_000, 2, 5, 4, false))
	}
	@Test fun noOverlappingSummary() = assertFalse(SummaryGate.shouldRun(on, 200_000, 100_000, 2, 5, 4, true))
	@Test fun cloudRequirementConfigurable() =
		assertTrue(SummaryGate.shouldRun(on.copy(requireCloudBeforeFirstSummary = false), 90_000, null, 0, 1, 0, false))

	// ---- dedupe ----
	@Test fun provisionalExcludesCloudCoveredAudio() {
		val spans = listOf(CloudSpan(0, 60_000, "雲端一"), CloudSpan(60_000, 120_000, "", ok = false, error = "x"))
		val offline = listOf(OfflineLine(1_000, "離線一"), OfflineLine(59_000, "離線二"), OfflineLine(61_000, "離線三"), OfflineLine(130_000, "離線四"))
		assertEquals(listOf("離線三", "離線四"), TranscriptMerge.provisional(spans, offline).map { it.text })
		assertEquals(60_000L, TranscriptMerge.coveredEndMs(spans))
	}

	@Test fun summaryPromptHasPreviousPointsAndMarksProvisional() {
		val p = TranscriptMerge.summaryPrompt("自訂", "- 舊重點", "新雲端", "暫定文字")
		assertTrue(p.startsWith("自訂"))
		assertTrue(p.contains("- 舊重點") && p.contains("新雲端") && p.contains("暫定") && p.contains("暫定文字"))
	}

	// ---- queue / stale ----
	@Test fun boundedQueueDropsOldestAndCounts() {
		val q = BoundedChunkQueue<Int>(2)
		assertNull(q.offer(1)); assertNull(q.offer(2))
		assertEquals(1, q.offer(3))
		assertEquals(1, q.droppedCount)
		assertEquals(2, q.poll()); assertEquals(3, q.poll()); assertNull(q.poll())
	}

	@Test fun sessionGuardRejectsStale() {
		val g = SessionGuard(); g.start("a"); assertTrue(g.isCurrent("a")); g.start("b")
		assertFalse(g.isCurrent("a")); g.close(); assertFalse(g.isCurrent("b"))
	}

	// ---- session end-to-end with fakes (no network) ----
	private fun pcm(seconds: Int, rate: Int = 100) = ShortArray(seconds * rate)

	@Test fun sessionChunksInOrderAndSummarizesAfterFirstCloud() = runTest {
		val calls = mutableListOf<Long>()
		val prompts = mutableListOf<String>()
		val s = LiveCloudSession(backgroundScope, on, "s1",
			transcribe = { _, start, _ -> calls += start; CallOutcome.Text("雲端@$start") },
			summarize = { p -> prompts += p; CallOutcome.Text("重點${prompts.size}") },
			sampleRate = 100)
		repeat(60) { s.onAudio(pcm(1), 100) }   // 60 s → first chunk
		advanceUntilIdle(); runCurrent()
		assertEquals(listOf(0L), calls)
		assertTrue("no summary before 90 s", prompts.isEmpty())
		repeat(30) { s.onAudio(pcm(1), 100) }   // 90 s
		advanceUntilIdle(); runCurrent()
		assertEquals(1, prompts.size)
		assertEquals("重點1", s.state.value.keyPoints)
		repeat(30) { s.onAudio(pcm(1), 100) }   // 120 s → second chunk + summary
		advanceUntilIdle(); runCurrent()
		assertEquals(listOf(0L, 60_000L), calls)
		assertEquals(2, prompts.size)
		assertTrue(prompts[1].contains("重點1") && prompts[1].contains("雲端@60000") && !prompts[1].contains("雲端@0\n"))
		val end = s.finish()
		assertEquals(2, end.cloudSpans.size)
	}

	@Test fun summaryFailureKeepsLastGoodKeyPoints() = runTest {
		var fail = false
		val s = LiveCloudSession(backgroundScope, on.copy(requireCloudBeforeFirstSummary = false, firstSummaryDelaySec = 0, summaryIntervalSec = 15), "s",
			transcribe = { _, _, _ -> CallOutcome.Text("t") },
			summarize = { if (fail) CallOutcome.Failure("HTTP 500", false) else CallOutcome.Text("好結果") }, sampleRate = 100)
		s.onOfflineLines(listOf(OfflineLine(0, "a")))
		s.onAudio(pcm(1), 100); advanceUntilIdle(); runCurrent()
		assertEquals("好結果", s.state.value.keyPoints)
		fail = true
		s.onOfflineLines(listOf(OfflineLine(0, "a"), OfflineLine(500, "b")))
		repeat(20) { s.onAudio(pcm(1), 100) }; advanceUntilIdle(); runCurrent()
		assertEquals("好結果", s.state.value.keyPoints)
		assertEquals("HTTP 500", s.state.value.summaryError)
	}

	@Test fun staleResultsIgnoredAfterCancel() = runTest {
		val gate = CompletableDeferred<Unit>()
		val s = LiveCloudSession(backgroundScope, on, "s",
			transcribe = { _, _, _ -> gate.await(); CallOutcome.Text("遲到") },
			summarize = { CallOutcome.Text("x") }, sampleRate = 100)
		repeat(60) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		s.cancel(); gate.complete(Unit); advanceUntilIdle(); runCurrent()
		assertTrue(s.state.value.cloudSpans.none { it.text == "遲到" })
	}

	@Test fun finishFlushesTailChunk() = runTest {
		val calls = mutableListOf<Pair<Long, Long>>()
		val s = LiveCloudSession(backgroundScope, on, "s",
			transcribe = { _, a, b -> calls += a to b; CallOutcome.Text("尾") },
			summarize = { CallOutcome.Text("終") }, sampleRate = 100)
		repeat(75) { s.onAudio(pcm(1), 100) }
		val end = s.finish()
		assertEquals(listOf(0L to 60_000L, 60_000L to 75_000L), calls)
		assertEquals("終", end.keyPoints)
	}

	@Test fun slowCloudDelaysFirstSummary() = runTest {
		val gate = CompletableDeferred<Unit>()
		var summaries = 0
		val s = LiveCloudSession(backgroundScope, on, "s",
			transcribe = { _, _, _ -> gate.await(); CallOutcome.Text("慢") },
			summarize = { summaries++; CallOutcome.Text("x") }, sampleRate = 100)
		repeat(100) { s.onAudio(pcm(1), 100) }; advanceUntilIdle(); runCurrent()
		assertEquals(0, summaries)
		gate.complete(Unit); advanceUntilIdle(); runCurrent()
		assertEquals(1, summaries)
	}

	// ---- playback selection ----
	private fun rec(id: String, src: TranscriptSource, at: Long, status: RunStatus = RunStatus.SUCCESS, text: String = "t", segs: List<SegmentResult> = emptyList()) =
		TranscriptRecord(id, 1, src, Provenance(engine = "e", model = "m"), at, status = status, text = text, segments = segs)

	@Test fun playbackPrefersFinishedCloud() {
		val list = listOf(rec("o", TranscriptSource.OFFLINE, 3), rec("c", TranscriptSource.CLOUD, 2), rec("f", TranscriptSource.CLOUD, 4, RunStatus.FAILED, ""))
		assertEquals("c", PlaybackTranscripts.defaultSelection(list)?.id)
		assertEquals(listOf("o", "c"), PlaybackTranscripts.selectable(list).map { it.id })
	}

	@Test fun untimedTranscriptHasNoFakeSync() {
		val lines = PlaybackTranscripts.lines(rec("o", TranscriptSource.OFFLINE, 1, text = "一\n\n二"))
		assertEquals(listOf("一", "二"), lines.map { it.text })
		assertEquals(-1, PlaybackTranscripts.activeIndex(lines, 0))
	}

	@Test fun timedTranscriptFollowsPosition() {
		val segs = listOf(SegmentResult(0, 0, 60_000, SegmentStatus.OK, "甲"), SegmentResult(1, 60_000, 120_000, SegmentStatus.OK, "乙"), SegmentResult(2, 120_000, 180_000, SegmentStatus.SILENT, ""))
		val lines = PlaybackTranscripts.lines(rec("c", TranscriptSource.CLOUD, 1, segs = segs))
		assertEquals(2, lines.size)
		assertEquals(1, PlaybackTranscripts.activeIndex(lines, 75_000))
		assertEquals(-1, PlaybackTranscripts.activeIndex(lines, 150_000))
	}
}

class SummaryProbeTests {
	private val base = com.eva.cloudtranscribe.live.LiveCloudSettings(summaryBaseUrl = "https://rone.example/v1", summaryPrompt = "自訂重點Prompt")
	private class Fake(val code: Int = 200, val body: String = """{"choices":[{"message":{"content":"- 報價單週三前完成"}}]}""", val ex: java.io.IOException? = null) : com.eva.cloudtranscribe.api.HttpTransport {
		val urls = mutableListOf<String>(); val headers = mutableListOf<Map<String, String>>(); val bodies = mutableListOf<String>()
		override fun postJson(url: String, headers: Map<String, String>, body: String, timeoutSeconds: Int): com.eva.cloudtranscribe.api.HttpResponse {
			urls += url; this.headers += headers; bodies += body; ex?.let { throw it }
			return com.eva.cloudtranscribe.api.HttpResponse(code, body = this.body)
		}
	}
	private fun run(s: com.eva.cloudtranscribe.live.LiveCloudSettings, f: Fake, sk: String? = "SUMKEY", ak: String? = "ASRKEY") =
		com.eva.cloudtranscribe.live.SummaryProbe.run(s, sk, ak, f)

	@Test fun usesSeparateEndpointModelKeyAndPrompt() {
		val f = Fake(); val r = run(base, f)
		assertTrue(r, r.startsWith("摘要測試成功"))
		assertEquals("https://rone.example/v1/chat/completions", f.urls.single())
		assertEquals("Bearer SUMKEY", f.headers.single()["Authorization"])
		assertTrue(f.bodies.single().contains("deepseek-v4.1-flash") && f.bodies.single().contains("自訂重點Prompt") && f.bodies.single().contains("報價單"))
		assertTrue(f.bodies.single().contains("\"max_tokens\":200"))
		assertFalse(f.bodies.single().contains("reasoning_effort"))
	}
	@Test fun reuseOnlyWhenExplicit() {
		val f = Fake(); run(base.copy(summaryReuseTranscribeKey = true), f)
		assertEquals("Bearer ASRKEY", f.headers.single()["Authorization"])
		assertTrue(run(base, Fake(), sk = null).contains("尚未設定摘要 API Key"))
		assertTrue(run(base.copy(summaryReuseTranscribeKey = true), Fake(), ak = null).contains("轉錄 Key 尚未設定"))
	}
	@Test fun validatesEndpointAndModelWithoutCalling() {
		val f = Fake()
		assertTrue(run(base.copy(summaryBaseUrl = "http://x"), f).contains("HTTPS"))
		assertTrue(run(base.copy(summaryModel = ""), f).contains("模型"))
		assertTrue(f.urls.isEmpty())
	}
	@Test fun worksWhenAdvancedModeDisabled() = assertFalse(base.enabled).also { assertTrue(run(base, Fake()).startsWith("摘要測試成功")) }
	@Test fun authErrorIsSafeAndNotRetried() {
		val f = Fake(401, """{"error":"bad key SUMKEY secret-detail"}""")
		val r = run(base, f)
		assertTrue(r.contains("HTTP 401")); assertFalse(r.contains("SUMKEY")); assertFalse(r.contains("secret-detail"))
		assertEquals(1, f.urls.size)
	}
	@Test fun timeoutAndRateLimitAndEmpty() {
		assertTrue(run(base, Fake(ex = java.net.SocketTimeoutException("http://SUMKEY"))).contains("逾時"))
		assertTrue(run(base, Fake(429)).contains("HTTP 429"))
		assertTrue(run(base, Fake(body = """{"choices":[{"message":{"content":""}}]}""")).contains("未回傳有效文字"))
	}
	@Test fun previewRedactsKey() {
		val r = run(base, Fake(body = """{"choices":[{"message":{"content":"echo SUMKEY ok"}}]}"""))
		assertFalse(r.contains("SUMKEY"))
	}
	@Test fun promptUsesEmptyPreviousSummary() =
		assertTrue(com.eva.cloudtranscribe.live.SummaryProbe.buildPrompt(base).contains("（尚無）"))
}

class LiveMergeTests {
	private fun m(spans: List<CloudSpan>, off: List<OfflineLine>, untimed: List<String> = emptyList()) =
		com.eva.cloudtranscribe.live.LiveMerge.merge(spans, off, untimed)

	@Test fun beforeCloudShowsAllOfflineProvisional() =
		assertEquals(listOf("a", "b"), m(emptyList(), listOf(OfflineLine(0, "a"), OfflineLine(5_000, "b"))).map { it.text })

	@Test fun cloudReplacesOnlyCoveredRangeKeepsNewestOffline() {
		val r = m(listOf(CloudSpan(0, 60_000, "雲一")), listOf(OfflineLine(1_000, "離一"), OfflineLine(61_000, "離二")))
		assertEquals(listOf("雲一" to K.CLOUD, "離二" to K.OFFLINE_PROVISIONAL), r.map { it.text to it.kind })
	}

	@Test fun outOfOrderSpansAndGapsSortedByTime() {
		val r = m(listOf(CloudSpan(120_000, 180_000, "雲三"), CloudSpan(0, 60_000, "雲一")),
			listOf(OfflineLine(70_000, "離gap"), OfflineLine(130_000, "離覆蓋")))
		assertEquals(listOf("雲一", "離gap", "雲三"), r.map { it.text })
	}

	@Test fun failedChunkKeepsOfflineAndMarker() {
		val r = m(listOf(CloudSpan(0, 60_000, "", ok = false, error = "HTTP 500")), listOf(OfflineLine(1_000, "離")))
		assertEquals(listOf(K.CLOUD_FAILED, K.OFFLINE_PROVISIONAL), r.map { it.kind })
	}

	@Test fun failedLaterCoveredIsHidden() {
		val r = m(listOf(CloudSpan(0, 60_000, "", ok = false), CloudSpan(0, 60_000, "雲")), emptyList())
		assertEquals(listOf("雲"), r.map { it.text })
	}

	@Test fun emptyCloudSpanStillCoversOffline() =
		assertTrue(m(listOf(CloudSpan(0, 60_000, "")), listOf(OfflineLine(1_000, "雜訊"))).isEmpty())

	@Test fun partialOverlapUsesLineStartOnly() {
		// offline line starting at 59.5s belongs to the covered chunk; one at 60s does not
		val r = m(listOf(CloudSpan(0, 60_000, "雲")), listOf(OfflineLine(59_500, "跨界"), OfflineLine(60_000, "下一段")))
		assertEquals(listOf("雲", "下一段"), r.map { it.text })
	}

	@Test fun untimedVoskNeverAligned() {
		val r = m(listOf(CloudSpan(0, 60_000, "雲")), emptyList(), listOf("vosk一", "vosk二"))
		assertEquals(listOf(K.CLOUD, K.OFFLINE_UNALIGNED, K.OFFLINE_UNALIGNED), r.map { it.kind })
		assertNull(r[1].startMs)
	}

	@Test fun latestCloudEnd() = assertEquals(120_000L,
		com.eva.cloudtranscribe.live.LiveMerge.latestCloudEndMs(listOf(CloudSpan(0, 60_000, "a"), CloudSpan(60_000, 120_000, "b"), CloudSpan(120_000, 180_000, "", ok = false))))
}
