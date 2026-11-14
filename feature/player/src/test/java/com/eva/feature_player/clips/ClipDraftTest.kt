package com.eva.feature_player.clips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClipDraftTest {

	@Test
	fun `draft from a range uses the real times and a plain title`() {
		val draft = ClipDraft.of(5_000L..35_000L)
		assertEquals("0:05", draft.startText)
		assertEquals("0:35", draft.endText)
		assertEquals("片段 0:05–0:35", draft.title)
		assertNull(draft.problem(totalMs = 60_000L))
	}

	@Test
	fun `bad text or out of range times cannot be saved`() {
		assertNotNull(ClipDraft("x", "0:10", "t").problem(60_000L))
		assertNotNull(ClipDraft("0:10", "0:05", "t").problem(60_000L))
		assertNotNull(ClipDraft("0:00", "1:10", "t").problem(60_000L))
	}

	@Test
	fun `max minutes is clamped to the 10 minute hard cap`() {
		assertEquals(10 * 60_000L, ClipDraft("0:00", "0:10", "t", maxMinutesText = "99").maxMs)
		assertEquals(3 * 60_000L, ClipDraft("0:00", "0:10", "t", maxMinutesText = "").maxMs)
		// 4 minutes exceeds the default 3 but passes once the user raises the limit to 5
		val hour = 3_600_000L
		assertNotNull(ClipDraft("0:00", "4:00", "t").problem(hour))
		assertNull(ClipDraft("0:00", "4:00", "t", maxMinutesText = "5").problem(hour))
	}
}
