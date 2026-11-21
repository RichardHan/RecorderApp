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
