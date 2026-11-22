package io.github.richardhan.voicenote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipTimeTest {
	@Test fun readsMinutesOrTotalSecondsWithFractionalPrecision() {
		assertEquals(62_500, parseClipTime("1:02.5"))
		assertEquals(62_500, parseClipTime("62,50"))
		assertEquals(1, parseClipTime("0:00.001"))
		assertEquals(3_600_000, parseClipTime("60:00"))
	}

	@Test fun editingDoesNotMoveFrameBoundariesThroughRounding() {
		for (ms in listOf(0, 7, 20, 507, 999, 1000, 60_023, 3_600_999, Int.MAX_VALUE)) {
			assertEquals(ms, parseClipTime(formatClipTime(ms)))
		}
	}

	@Test fun rejectsPartialMalformedAndOverflowingValues() {
		for (text in listOf("", "-1", "1:", "1:60", "1:9", "3.", "1.0001", "5:02:01", "2147483.648", "9999999:59")) {
			assertNull(text, parseClipTime(text))
		}
	}
}
