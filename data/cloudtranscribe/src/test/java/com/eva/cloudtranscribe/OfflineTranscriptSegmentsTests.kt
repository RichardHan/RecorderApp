package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.model.*
import org.junit.Assert.*
import org.junit.Test

class OfflineTranscriptSegmentsTests {
	private fun record(segments: List<SegmentResult>) = TranscriptRecord(
		"offline", 1L, TranscriptSource.OFFLINE, Provenance("sherpa", model = "model"),
		1L, status = RunStatus.SUCCESS, text = "existing text", segments = segments,
	)

	@Test fun pcmSampleBoundsBecomeRealPlaybackTimes() {
		val segment = OfflineTranscriptSegments.fromSamples(0, 32_000, 48_000, "會議決策")
		assertEquals(2000L, segment.startMs); assertEquals(5000L, segment.endMs)
		val line = PlaybackTranscripts.lines(record(listOf(segment))).single()
		assertEquals("會議決策", line.text); assertEquals(2000L, line.startMs)
		assertEquals(0, PlaybackTranscripts.activeIndex(listOf(line), 2500))
		assertEquals(-1, PlaybackTranscripts.activeIndex(listOf(line), 5000))
	}

	@Test fun preservesSilenceGapsInsteadOfStretchingPreviousSpeech() {
		val a = OfflineTranscriptSegments.fromSamples(0, 0, 16_000, "第一句")
		val b = OfflineTranscriptSegments.fromSamples(1, 80_000, 16_000, "第二句")
		val lines = PlaybackTranscripts.lines(record(listOf(a, b)))
		assertEquals(-1, PlaybackTranscripts.activeIndex(lines, 3000))
		assertEquals(1, PlaybackTranscripts.activeIndex(lines, 5500))
	}

	@Test fun failuresRemainFailuresAndDoNotBecomeClickableEvidence() {
		val bad = OfflineTranscriptSegments.fromSamples(0, 0, 16_000, "失敗提示", "decode failed")
		assertEquals(SegmentStatus.FAILED, bad.status)
		assertEquals("decode failed", bad.error)
		assertNull(PlaybackTranscripts.timedLines(record(listOf(bad))))
	}

	@Test fun blankRecognitionIsNotAQuote() {
		assertEquals(SegmentStatus.EMPTY, OfflineTranscriptSegments.fromSamples(0, 0, 16_000, "  ").status)
	}

	@Test fun oldUntimedTranscriptsKeepTheirTextOnlyFallback() {
		val line = PlaybackTranscripts.lines(record(emptyList())).single()
		assertEquals("existing text", line.text); assertNull(line.startMs)
	}

	@Test(expected = IllegalArgumentException::class) fun rejectsNegativeAudioOffsets() {
		OfflineTranscriptSegments.fromSamples(0, -1, 16_000, "invalid")
	}
}
