package com.eva.recordings.domain.clips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipRangePolicyTest {

	@Test
	fun `bookmark at 20s in a 60s recording gives 5s to 35s`() {
		assertEquals(5_000L..35_000L, ClipRangePolicy.aroundBookmark(20_000L, 60_000L))
	}

	@Test
	fun `bookmark near the start clamps to zero and near the end clamps to total`() {
		assertEquals(0L..20_000L, ClipRangePolicy.aroundBookmark(5_000L, 60_000L))
		assertEquals(40_000L..60_000L, ClipRangePolicy.aroundBookmark(55_000L, 60_000L))
	}

	@Test
	fun `from position keeps a 30s clip inside the recording`() {
		assertEquals(10_000L..40_000L, ClipRangePolicy.fromPosition(10_000L, 120_000L))
		assertEquals(90_000L..120_000L, ClipRangePolicy.fromPosition(110_000L, 120_000L))
		assertEquals(0L..20_000L, ClipRangePolicy.fromPosition(5_000L, 20_000L))
	}

	@Test
	fun `validation rejects short, long, reversed and out of range`() {
		assertNull(ClipRangePolicy.validate(0L, 30_000L, 60_000L))
		assertEquals(ClipRangeProblem.TOO_SHORT, ClipRangePolicy.validate(0L, 999L, 60_000L))
		assertEquals(ClipRangeProblem.START_AFTER_END, ClipRangePolicy.validate(5_000L, 5_000L, 60_000L))
		assertEquals(ClipRangeProblem.OUT_OF_RECORDING, ClipRangePolicy.validate(-1L, 5_000L, 60_000L))
		assertEquals(ClipRangeProblem.OUT_OF_RECORDING, ClipRangePolicy.validate(0L, 61_000L, 60_000L))
		val hour = 3_600_000L
		assertEquals(ClipRangeProblem.TOO_LONG, ClipRangePolicy.validate(0L, 4 * 60_000L, hour))
		// user may raise the limit, but never above the 10 minute hard cap
		assertNull(ClipRangePolicy.validate(0L, 4 * 60_000L, hour, maxMs = 5 * 60_000L))
		assertEquals(ClipRangeProblem.TOO_LONG, ClipRangePolicy.validate(0L, 11 * 60_000L, hour, maxMs = 60 * 60_000L))
	}

	@Test
	fun `time parsing and formatting round trip`() {
		assertEquals(83_000L, ClipRangePolicy.parseTime("1:23"))
		assertEquals(3_723_000L, ClipRangePolicy.parseTime("1:02:03"))
		assertEquals(45_000L, ClipRangePolicy.parseTime("45"))
		assertNull(ClipRangePolicy.parseTime("1:75"))
		assertNull(ClipRangePolicy.parseTime("abc"))
		assertNull(ClipRangePolicy.parseTime(""))
		assertEquals("1:23", ClipRangePolicy.formatMs(83_400L))
		assertEquals("1:02:03", ClipRangePolicy.formatMs(3_723_000L))
		assertEquals("片段 0:05–0:35", ClipRangePolicy.defaultTitle(5_000L, 35_000L))
	}
}
