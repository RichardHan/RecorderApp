package io.github.richardhan.voicenote

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class SpeakerTurnsTest {
	@Test fun assignsByLongestOverlapAndLeavesAmbiguousSpeechUnassigned() {
		val turns = listOf(SpeakerTurn(0.0, 3.0, 1), SpeakerTurn(3.0, 5.0, 2))
		assertEquals(1, closestTurn(turns, TimedText(2.5, 4.5, "longer overlap")))
		assertNull(closestTurn(turns, TimedText(2.0, 4.0, "equal overlap")))
		assertNull(closestTurn(turns, TimedText(5.0, 6.0, "after speech")))
	}

	@Test fun exportKeepsShortTurnsAndDoesNotAttributeAmbiguousText() {
		val timeline = SpeakerTimeline(listOf(SpeakerTurn(0.125, 0.625, 1), SpeakerTurn(0.625, 1.75, 2)), mapOf(1 to "小林"))
		val segments = listOf(TimedText(0.25, 0.5, "你好"), TimedText(0.5, 0.75, "ambiguous"))
		val output = buildString { writeTimelineText(this, timeline, segments, null) { "話者 $it" } }
		assertEquals("0:00.125 – 0:00.625 · 小林\n你好\n\n0:00.625 – 0:01.75 · 話者 2\n", output)
	}

	@Test fun filteringDoesNotReassignOtherSpeakersWordsOrResetTheClock() {
		val timeline = SpeakerTimeline(listOf(SpeakerTurn(0.0, 3.0, 1), SpeakerTurn(2.0, 4.0, 2), SpeakerTurn(6.0, 7.0, 2)), emptyMap())
		val segments = listOf(TimedText(1.0, 2.5, "other speaker"), TimedText(2.0, 3.0, "ambiguous"), TimedText(3.5, 4.0, "selected"))
		val output = buildString { writeTimelineText(this, timeline, segments, 2) { "Speaker $it" } }
		assertEquals("0:02 – 0:04 · Speaker 2\nselected\n\n0:06 – 0:07 · Speaker 2\n", output)
	}

	@Test fun mixesStereoAndPreservesResamplingPhaseAcrossBuffers() {
		val samples = MonoSamples()
		repeat(3) {
			val frame = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
			frame.putShort(16384).putShort(0).flip()
			samples.append(frame, 2, 48000, false)
		}
		assertArrayEquals(floatArrayOf(0.25f), samples.finish(), 1e-5f)
	}
}
