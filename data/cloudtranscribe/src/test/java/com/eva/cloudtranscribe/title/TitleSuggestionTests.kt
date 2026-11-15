package com.eva.cloudtranscribe.title

import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleSuggestionTests {

	private fun summary(id: String, at: Long, text: String, status: RunStatus = RunStatus.SUCCESS) =
		SummaryRecord(id, 1, "t", Provenance(engine = "cloud-summary", model = "m"), at, status, text)

	@Test
	fun local_takes_first_meaningful_point_and_cleans_it() {
		assertEquals("Q3 報價上限五十萬", TitleSuggestion.fromSummary("## 重點\n決議：\n- **Q3 報價**上限五十萬。\n- 其他"))
		assertEquals("1. 第二項", TitleSuggestion.fromSummary("1. 1. 第二項").let { it }) // only one bullet marker removed
		assertNull(TitleSuggestion.fromSummary("目前無重點"))
		assertNull(TitleSuggestion.fromSummary("- 。"))
	}

	@Test
	fun local_is_cut_to_thirty_chars() {
		val t = TitleSuggestion.fromSummary("- " + "字".repeat(50))!!
		assertEquals(TitleSuggestion.LOCAL_MAX, t.length)
	}

	@Test
	fun latest_usable_skips_failed_empty_and_no_points() {
		val list = listOf(
			summary("a", 5, "目前無重點"),
			summary("b", 4, "", RunStatus.SUCCESS),
			summary("c", 3, "- x", RunStatus.FAILED),
			summary("d", 2, "- 真的重點"),
			summary("e", 1, "- 舊的"),
		)
		assertEquals("d", TitleSuggestion.latestUsable(list)?.id)
		assertNull(TitleSuggestion.latestUsable(list.take(3)))
	}

	@Test
	fun ai_reply_is_unwrapped_and_validated() {
		assertEquals("Q3 報價會議", TitleSuggestion.parseAi("標題：「Q3 報價會議」"))
		assertEquals("Weekly Sync", TitleSuggestion.parseAi("\n\"Weekly Sync\"\n說明：…"))
		assertEquals("產品路線圖", TitleSuggestion.parseAi("《產品路線圖》。"))
		assertNull(TitleSuggestion.parseAi("好"))
		assertNull(TitleSuggestion.parseAi("x".repeat(41)))
		assertNull(TitleSuggestion.parseAi("   "))
		assertNull(TitleSuggestion.parseAi("目前無重點"))
	}

	@Test
	fun illegal_file_name_characters_are_removed() {
		assertEquals("A B C D", TitleSuggestion.sanitize("A/B:C*?\"D"))
		assertEquals("報價 v2", TitleSuggestion.parseAi("報價 / v2"))
	}

	@Test
	fun prompt_only_contains_bounded_summary() {
		val p = TitleSuggestion.prompt("- 重點" + "字".repeat(10_000))
		assertTrue(p.contains("摘要：\n- 重點"))
		assertTrue(p.length < TitleSuggestion.MAX_SUMMARY_CHARS + 200)
	}
}
