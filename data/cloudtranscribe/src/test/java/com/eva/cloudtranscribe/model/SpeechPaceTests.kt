package com.eva.cloudtranscribe.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechPaceTests {

	@Test
	fun units_count_han_characters_and_english_words() {
		assertEquals(4 + 3, SpeechPace.units("今天開會 send the deck"))
		assertEquals(2, SpeechPace.units("don't Q3"))
	}

	@Test
	fun pace_uses_only_real_timed_lines() {
		val lines = listOf(PlaybackLine("一二三四五六", 0, 60_000), PlaybackLine("七八九十", 60_000, 90_000))
		val r = SpeechPace.report(lines)
		assertEquals(10, r.units)
		assertEquals(10 / 1.5, r.perMinute!!, 0.001)
		assertEquals(60_000L, r.fastest!!.startMs) // 4 字 / 0.5 分 = 8 > 6
		assertEquals(0L, r.slowest!!.startMs)
	}

	@Test
	fun untimed_transcript_has_no_pace() {
		val r = SpeechPace.report(listOf(PlaybackLine("嗯 我們開始"), PlaybackLine("然後報價")))
		assertNull(r.perMinute)
		assertNull(r.fastest)
		assertNull(r.slowest)
		assertEquals(9, r.units)
	}

	@Test
	fun short_lines_do_not_count_for_fastest_or_slowest() {
		val r = SpeechPace.report(listOf(PlaybackLine("一二三四五六七八九十", 0, 5_000), PlaybackLine("一二", 5_000, 65_000)))
		assertEquals(5_000L, r.fastest!!.startMs)
		assertNull(r.slowest) // only one eligible line
	}

	@Test
	fun fillers_respect_word_boundaries_and_longest_first() {
		val counts = SpeechPace.fillerCounts("對對對，那個嗯嗯，就是 Um, you  know, it's likely unlike LIKE uhh uh").toMap()
		assertEquals(1, counts["對對對"])
		assertEquals(1, counts["那個"])
		assertEquals(2, counts["嗯"])
		assertEquals(1, counts["就是"])
		assertEquals(1, counts["um"])
		assertEquals(1, counts["you know"])
		assertEquals(1, counts["like"])
		assertEquals(1, counts["uh"])
	}

	@Test
	fun top_fillers_are_sorted_and_rate_is_per_thousand_units() {
		val r = SpeechPace.report(listOf(PlaybackLine("嗯嗯嗯然後然後那個" + "字".repeat(91))))
		assertEquals(listOf("嗯" to 3, "然後" to 2, "那個" to 1), r.fillers)
		assertEquals(100, r.units)
		assertEquals(60.0, r.fillersPerThousand, 0.001)
	}
}
