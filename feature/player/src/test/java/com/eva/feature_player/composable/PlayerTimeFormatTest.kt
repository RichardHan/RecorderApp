package com.eva.feature_player.composable

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerTimeFormatTest {
	@Test
	fun `formats minutes and hours`() {
		assertEquals("00:00", fmtSec(0))
		assertEquals("00:59", fmtSec(59))
		assertEquals("01:00", fmtSec(60))
		assertEquals("29:29", fmtSec(29 * 60 + 29))
		assertEquals("1:00:00", fmtSec(3600))
		assertEquals("1:01:01", fmtSec(3661))
	}
}
