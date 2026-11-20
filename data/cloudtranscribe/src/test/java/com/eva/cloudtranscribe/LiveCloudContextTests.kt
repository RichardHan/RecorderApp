package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.engine.Prompts
import com.eva.cloudtranscribe.live.LiveAudioOverlap
import com.eva.cloudtranscribe.live.LiveBoundaryRefine
import com.eva.cloudtranscribe.live.LiveCloudSession
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.live.LiveKnownTerms
import com.eva.cloudtranscribe.live.LiveTranscribeJob
import com.eva.cloudtranscribe.live.LiveTranscribePrompt
import com.eva.cloudtranscribe.live.LiveTranscriptOverlap
import com.eva.cloudtranscribe.model.CloudUsage
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
class LiveAudioOverlapTests {

	@Test
	fun firstChunkHasNoOverlapAndLaterChunksPrependConfiguredTail() {
		val rate = 100
		val first = ShortArray(30 * rate) { 1 }
		assertEquals(0, LiveAudioOverlap.tail(first, 0, rate).size)
		val tail = LiveAudioOverlap.tail(first, 3_000, rate)
		assertEquals(300, tail.size)
		assertTrue(tail.all { it == 1.toShort() })
		val secondFresh = ShortArray(30 * rate) { 2 }
		val uploaded = LiveAudioOverlap.prepend(tail, secondFresh)
		assertEquals(3_300, uploaded.size)
		assertTrue(uploaded.take(300).all { it == 1.toShort() })
		assertTrue(uploaded.drop(300).all { it == 2.toShort() })
		assertEquals(33_000L, LiveAudioOverlap.uploadedMs(30_000, 3_000))
	}
}

class LiveTranscriptOverlapTests {

	@Test
	fun exactAndFuzzyBoundaryDedupesWithoutDroppingNewWords() {
		assertEquals(
			"再討論時程。",
			LiveTranscriptOverlap.trimLeadingOverlap("我們決定週三開會。", "我們決定週三開會。再討論時程。"),
		)
		assertEquals(
			"再討論時程",
			LiveTranscriptOverlap.trimLeadingOverlap("我們決定週三開會", "我們決定週三開會，再討論時程"),
		)
		assertEquals(
			"然後接著說明 API。",
			LiveTranscriptOverlap.trimLeadingOverlap("先看 503 問題。", "先看 503 問題。然後接著說明 API。"),
		)
		// no confident overlap: keep the incoming text
		assertEquals("完全不同的一句。", LiveTranscriptOverlap.trimLeadingOverlap("週三開會。", "完全不同的一句。"))
		// nothing invented when previous is empty
		assertEquals("第一段。", LiveTranscriptOverlap.trimLeadingOverlap("", "第一段。"))
	}

	@Test
	fun lastSentencesKeepsOnlyTheTail() {
		assertEquals(
			"二。三。",
			LiveTranscriptOverlap.lastSentences("零。一。二。三。", 2),
		)
		assertEquals("只有一句", LiveTranscriptOverlap.lastSentences("只有一句", 3))
	}
}

class LiveKnownTermsAndPromptTests {

	@Test
	fun termsComeOnlyFromTranscriptVocabularyAndNotes() {
		val fromSpeech = LiveKnownTerms.extract("大家好，我是 Hank。我們在修 503 和 RecorderApp 的 API latency。")
		assertTrue(fromSpeech.contains("Hank"))
		assertTrue(fromSpeech.contains("RecorderApp"))
		assertTrue(fromSpeech.contains("API"))
		assertFalse(fromSpeech.contains("大家好"))
		assertFalse(fromSpeech.any { it == "Speaker 1" || it.equals("speaker 2", true) })
		val seeds = LiveKnownTerms.fromSeeds(listOf("Charlie", "design"), "議程：和 Hank 對一下投影片")
		assertTrue(seeds.contains("Charlie") && seeds.contains("design") && seeds.contains("Hank"))
		assertEquals(listOf("Charlie", "Hank"), LiveKnownTerms.merge(listOf("Charlie"), listOf("Charlie", "Hank")))
	}

	@Test
	fun transcribePromptMarksContextAndForbidsReoutput() {
		val prompt = LiveTranscribePrompt.build(
			base = Prompts.TRANSCRIBE,
			overlapMs = 3_000,
			previousSentences = "Hank：正處理 503 問題。",
			knownTerms = listOf("Hank", "503"),
			enhance = true,
		)
		assertTrue(prompt.startsWith(Prompts.TRANSCRIBE))
		assertTrue(prompt.contains("不要再輸出"))
		assertTrue(prompt.contains("3") && prompt.contains("重疊"))
		assertTrue(prompt.contains("Hank：正處理 503 問題。"))
		assertTrue(prompt.contains("Hank") && prompt.contains("503"))
		assertTrue(prompt.contains("不可臆測"))
		assertEquals(Prompts.TRANSCRIBE, LiveTranscribePrompt.build(Prompts.TRANSCRIBE, 0, "", emptyList(), enhance = false))
		assertEquals(Prompts.TRANSCRIBE, LiveTranscribePrompt.build(Prompts.TRANSCRIBE, 0, "", emptyList(), enhance = true))
	}

	@Test
	fun transcribePromptsAreVerbatimCodeSwitching() {
		for (p in listOf(Prompts.TRANSCRIBE, Prompts.TRANSCRIBE_ALT)) {
			assertTrue(p.contains(Prompts.NO_SPEECH))
			assertTrue(p.contains("聽不清楚"))
			assertTrue(p.contains("verbatim") || p.contains("Verbatim"))
			assertFalse(p.contains("嗯"))
			assertTrue(p.contains("Traditional Chinese") || p.contains("English"))
			assertTrue(p.contains("paraphrase") || p.contains("意譯"))
		}
		assertTrue(Prompts.TRANSCRIBE.contains("Do not force a single language"))
		assertTrue(Prompts.TRANSCRIBE.contains("translate"))
		assertTrue(Prompts.TRANSCRIBE.contains("Taiwanese") || Prompts.TRANSCRIBE.contains("程式"))
		assertTrue(Prompts.TRANSCRIBE.contains("punctuation") || Prompts.TRANSCRIBE.contains("sentence"))
		assertTrue(Prompts.TRANSCRIBE.contains("Do not force a single language"))
		assertTrue(Prompts.TRANSCRIBE.contains("paraphrase"))
	}
}

class LiveBoundaryRefineTests {

	@Test
	fun punctuationAndKnownNameFixesAreAcceptedButAddedContentIsRejected() {
		val raw = "hank 正處理 503 問題我們下週再看"
		assertEquals(
			"Hank 正處理 503 問題。我們下週再看。",
			LiveBoundaryRefine.accept(raw, "Hank 正處理 503 問題。我們下週再看。"),
		)
		assertNull(LiveBoundaryRefine.accept(raw, raw + "。另外決議明年全面改用新架構並擴編十人。而且再加一段沒說過的話。"))
		assertNull(LiveBoundaryRefine.accept(raw, ""))
		assertNull(LiveBoundaryRefine.accept(raw, Prompts.NO_SPEECH))
		assertEquals(raw, LiveBoundaryRefine.accept(raw, raw))
		assertNull(LiveBoundaryRefine.accept("cloud chunk 1", "cloud chunk 2"))
	}
}

class LiveUsageOverlapTests {

	@Test
	fun usageCountsLogicalAudioOnceNotTheRepeatedOverlap() {
		val rec = TranscriptRecord(
			id = "live",
			recordingId = 1,
			source = TranscriptSource.CLOUD,
			provenance = Provenance(engine = "cloud-live", model = "m"),
			createdAtMillis = 1,
			status = RunStatus.SUCCESS,
			text = "a\nb",
			segments = listOf(
				SegmentResult(0, 0, 30_000, SegmentStatus.OK, "a", attempts = 1),
				// second chunk uploaded 33s (30 + 3 overlap) but logical range stays 30–60
				SegmentResult(1, 30_000, 60_000, SegmentStatus.OK, "b", attempts = 1),
			),
		)
		assertEquals(60_000L, CloudUsage.uploadedMs(rec))
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCloudContextSessionTests {

	private val on = LiveCloudSettings(
		enabled = true,
		consentAcceptedAtMillis = 1L,
		transcribeIntervalSec = 30,
		enhanceContextQuality = true,
		overlapSec = 3,
		refineBoundaries = true,
	)

	private fun pcm(seconds: Int, rate: Int = 100) = ShortArray(seconds * rate)

	@Test
	fun sessionCutsOverlapOnLaterChunksAndKeepsLogicalBounds() = runTest {
		val jobs = mutableListOf<LiveTranscribeJob>()
		val s = LiveCloudSession(
			backgroundScope, on, "s",
			transcribe = { job -> jobs += job; CallOutcome.Text("內容@${job.startMs}") },
			summarize = { CallOutcome.Text("k") },
			sampleRate = 100,
		)
		repeat(30) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertEquals(1, jobs.size)
		assertEquals(0L, jobs[0].overlapMs)
		assertEquals(0L to 30_000L, jobs[0].startMs to jobs[0].endMs)
		assertEquals(3_000, jobs[0].samples.size)
		repeat(30) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertEquals(2, jobs.size)
		assertEquals(3_000L, jobs[1].overlapMs)
		assertEquals(30_000L to 60_000L, jobs[1].startMs to jobs[1].endMs)
		assertEquals(3_300, jobs[1].samples.size)
		assertTrue(jobs[1].prompt.contains("不要再輸出"))
		assertTrue(jobs[1].previousSentences.contains("內容@0"))
		val end = s.finish()
		assertEquals(0L, end.cloudSpans[0].startMs)
		assertEquals(30_000L, end.cloudSpans[1].startMs)
		assertEquals(30_000L, end.cloudSpans[0].endMs)
		assertEquals(3_000L, end.lastChunkLatency?.overlapMs)
		assertEquals(33_000L, end.lastChunkLatency?.uploadedMs)
	}

	@Test
	fun qualityOffUploadsNoOverlap() = runTest {
		val jobs = mutableListOf<LiveTranscribeJob>()
		val s = LiveCloudSession(
			backgroundScope, on.copy(enhanceContextQuality = false), "s",
			transcribe = { job -> jobs += job; CallOutcome.Text("t") },
			summarize = { CallOutcome.Text("k") },
			sampleRate = 100,
		)
		repeat(60) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertTrue(jobs.all { it.overlapMs == 0L && it.samples.size == 3_000 })
		assertFalse(jobs[1].prompt.contains("前文脈絡"))
	}

	@Test
	fun sessionsDoNotLeakTermsOrText() = runTest {
		val firstJobs = mutableListOf<LiveTranscribeJob>()
		val a = LiveCloudSession(
			backgroundScope, on, "a",
			transcribe = { job -> firstJobs += job; CallOutcome.Text("大家好，我是 Hank。正在修 503。") },
			summarize = { CallOutcome.Text("k") },
			sampleRate = 100,
			seedTerms = listOf("Charlie"),
		)
		repeat(30) { a.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		repeat(30) { a.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertTrue(firstJobs[1].knownTerms.contains("Hank") || firstJobs[1].prompt.contains("Hank"))
		a.finish()

		val secondJobs = mutableListOf<LiveTranscribeJob>()
		val b = LiveCloudSession(
			backgroundScope, on, "b",
			transcribe = { job -> secondJobs += job; CallOutcome.Text("今天只排時程。") },
			summarize = { CallOutcome.Text("k") },
			sampleRate = 100,
		)
		repeat(30) { b.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertTrue(secondJobs.isNotEmpty())
		assertFalse(secondJobs[0].knownTerms.contains("Hank"))
		assertFalse(secondJobs[0].prompt.contains("Hank"))
		assertFalse(secondJobs[0].previousSentences.contains("503"))
		b.cancel()
	}

	@Test
	fun rawTextIsShownBeforeRefineAndFailureKeepsRaw() = runTest {
		val refineGate = CompletableDeferred<Unit>()
		val s = LiveCloudSession(
			backgroundScope, on, "s",
			transcribe = { CallOutcome.Text("hank 正處理 503 問題") },
			summarize = { CallOutcome.Text("k") },
			sampleRate = 100,
			refine = { refineGate.await(); CallOutcome.Text("Hank 正處理 503 問題。") },
		)
		repeat(30) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertEquals("hank 正處理 503 問題", s.state.value.cloudSpans.single().text)
		refineGate.complete(Unit)
		advanceUntilIdle(); runCurrent()
		assertEquals("Hank 正處理 503 問題。", s.state.value.cloudSpans.single().text)
		assertTrue((s.state.value.lastChunkLatency?.refineMs ?: -1) >= 0)
		s.finish()

		val keep = LiveCloudSession(
			backgroundScope, on, "keep",
			transcribe = { CallOutcome.Text("原始稿") },
			summarize = { CallOutcome.Text("k") },
			sampleRate = 100,
			refine = { CallOutcome.Failure("timeout", retryable = false) },
		)
		repeat(30) { keep.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertEquals("原始稿", keep.state.value.cloudSpans.single().text)
		keep.finish()
	}

	@Test
	fun refineDoesNotBlockTheNextUpload() = runTest {
		val started = mutableListOf<Long>()
		val refineGate = CompletableDeferred<Unit>()
		val s = LiveCloudSession(
			backgroundScope, on, "s",
			transcribe = { job -> started += job.startMs; CallOutcome.Text("段${job.startMs}") },
			summarize = { CallOutcome.Text("k") },
			sampleRate = 100,
			refine = { refineGate.await(); CallOutcome.Text(it) },
		)
		repeat(60) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertEquals(listOf(0L, 30_000L), started)
		assertEquals(2, s.state.value.cloudSpans.size)
		assertTrue(s.state.value.cloudSpans.all { it.text.startsWith("段") })
		refineGate.complete(Unit)
		s.finish()
	}

	@Test
	fun laterChunkCanFinishFirstButMergesInOrder() = runTest {
		val first = CompletableDeferred<Unit>()
		val firstStarted = CompletableDeferred<Unit>()
		val seen = mutableListOf<String>()
		val s = LiveCloudSession(
			backgroundScope, on, "s",
			transcribe = { job ->
				if (job.startMs == 0L) {
					firstStarted.complete(Unit)
					first.await()
					CallOutcome.Text("第一段")
				} else {
					CallOutcome.Text("第二段")
				}
			},
			summarize = { CallOutcome.Text("k") },
			sampleRate = 100,
		)
		repeat(60) { s.onAudio(pcm(1), 100) }
		advanceUntilIdle(); runCurrent()
		assertTrue(firstStarted.isCompleted)
		assertTrue(s.state.value.cloudSpans.isEmpty())
		assertTrue(s.state.value.inFlightTranscribes >= 1)
		first.complete(Unit)
		advanceUntilIdle(); runCurrent()
		assertEquals(listOf("第一段", "第二段"), s.state.value.cloudSpans.sortedBy { it.startMs }.map { it.text })
		s.state.value.cloudSpans.forEach { seen += it.text }
		s.finish()
	}

	@Test
	fun intervalCanBeFifteenSeconds() {
		val n = LiveCloudSettings(transcribeIntervalSec = 15).normalized()
		assertEquals(15, n.transcribeIntervalSec)
		assertEquals(15_000L, n.transcribeIntervalMs)
		assertEquals(15, LiveCloudSettings.MIN_TRANSCRIBE_INTERVAL)
		assertEquals(3, LiveCloudSettings(overlapSec = 3).normalized().overlapSec)
		assertEquals(5, LiveCloudSettings(overlapSec = 99).normalized().overlapSec)
		assertEquals(0L, LiveCloudSettings(enhanceContextQuality = false, overlapSec = 3).effectiveOverlapMs)
		assertEquals(3_000L, LiveCloudSettings(enhanceContextQuality = true, overlapSec = 3).effectiveOverlapMs)
	}
}
