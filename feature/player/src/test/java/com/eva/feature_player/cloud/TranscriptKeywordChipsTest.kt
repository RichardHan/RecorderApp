package com.eva.feature_player.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptKeywordChipsTest {
	@Test
	fun tapping_the_active_keyword_clears_and_others_replace() {
		assertEquals("報價", keywordQuery("", "報價"))
		assertEquals("", keywordQuery("報價 ", "報價"))
		assertEquals("", keywordQuery("api", "API"))
		assertEquals("Q3", keywordQuery("報價", "Q3"))
	}
}
