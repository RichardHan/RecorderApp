package com.eva.player_shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookmarkTickPositionsTest {

	@Test
	fun `empty or invalid total yields no ticks`() {
		assertTrue(BookmarkTickPositions.ratios(listOf(1_000L), 0L).isEmpty())
		assertTrue(BookmarkTickPositions.ratios(listOf(1_000L), -5L).isEmpty())
		assertTrue(BookmarkTickPositions.ratios(emptyList(), 60_000L).isEmpty())
	}

	@Test
	fun `maps timestamps onto the unit interval`() {
		val ratios = BookmarkTickPositions.ratios(
			positionsMs = listOf(0L, 15_000L, 30_000L, 60_000L),
			totalMs = 60_000L,
		)
		assertEquals(listOf(0f, 0.25f, 0.5f, 1f), ratios)
	}

	@Test
	fun `drops out of range and duplicate positions`() {
		val ratios = BookmarkTickPositions.ratios(
			positionsMs = listOf(-1L, 10_000L, 10_000L, 90_000L),
			totalMs = 60_000L,
		)
		assertEquals(listOf(10_000f / 60_000f), ratios)
	}
}
