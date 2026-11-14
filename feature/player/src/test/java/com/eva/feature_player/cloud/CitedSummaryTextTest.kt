package com.eva.feature_player.cloud

import com.eva.cloudtranscribe.citations.Citation
import org.junit.Assert.assertEquals
import org.junit.Test

class CitedSummaryTextTest {
	@Test
	fun citations_attach_to_their_line_sorted_by_time() {
		val lines = citedLines("- a\n- b", listOf(Citation(1, 5, 9_000, 10_000), Citation(1, 2, 3_000, 4_000)))
		assertEquals(emptyList<Citation>(), lines[0].second)
		assertEquals(listOf(3_000L, 9_000L), lines[1].second.map { it.startMs })
		assertEquals("- b", lines[1].first)
	}
}
