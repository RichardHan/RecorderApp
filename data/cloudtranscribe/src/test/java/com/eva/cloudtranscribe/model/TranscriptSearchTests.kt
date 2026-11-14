package com.eva.cloudtranscribe.model

import org.junit.Assert.*
import org.junit.Test

class TranscriptSearchTests {
	@Test fun `blank query has no matches`() {
		assertTrue(TranscriptSearch.matchingLines(listOf(PlaybackLine("hello")), "  ").isEmpty())
		assertTrue(TranscriptSearch.ranges("hello", "").isEmpty())
	}
	@Test fun `matching lines preserve original index and ignore case`() {
		val lines = listOf(PlaybackLine("Budget"), PlaybackLine("other"), PlaybackLine("BUDGET update"))
		assertEquals(listOf(0, 2), TranscriptSearch.matchingLines(lines, " budget "))
	}
	@Test fun `punctuation is literal not regex`() {
		assertEquals(listOf(2..4), TranscriptSearch.ranges("a [x] b", "[x]"))
		assertTrue(TranscriptSearch.ranges("abc", ".*").isEmpty())
	}
	@Test fun `CJK and emoji retain original character offsets`() {
		val text = "😀預算與預算"
		val ranges = TranscriptSearch.ranges(text, "預算")
		assertEquals(listOf(2..3, 5..6), ranges)
		assertEquals(listOf("預算", "預算"), ranges.map { text.substring(it) })
	}
	@Test fun `repeated occurrences use nonoverlapping highlights`() {
		assertEquals(listOf(0..1, 2..3), TranscriptSearch.ranges("aaaaa", "aa"))
	}
	@Test fun `navigation wraps in both directions`() {
		assertEquals(0, TranscriptSearch.next(2, 3, 1))
		assertEquals(2, TranscriptSearch.next(0, 3, -1))
		assertEquals(-1, TranscriptSearch.next(0, 0, 1))
		assertEquals(0, TranscriptSearch.next(-1, 3, 1))
	}
	@Test fun `search does not change timestamps or transcript text`() {
		val original = PlaybackLine("budget", 1200, 2500)
		assertEquals(listOf(0), TranscriptSearch.matchingLines(listOf(original), "budget"))
		assertEquals(PlaybackLine("budget", 1200, 2500), original)
	}
}
