package com.eva.cloudtranscribe.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryOutlineTests {

	@Test
	fun flat_bullets_become_one_root_with_leaves() {
		assertEquals(
			listOf(OutlineNode("重點", listOf(OutlineNode("決定 A"), OutlineNode("B 負責報價"), OutlineNode("1.5 億預算")))),
			SummaryOutline.local("- 決定 A\n• B 負責報價\n\n1.5 億預算"),
		)
	}

	@Test
	fun headings_topics_and_bold_lines_become_parents() {
		val summary = """
			會議開場說明
			## 決議
			- 採用 A 方案
			主題：時程
			1. 下週三交付
			2、月底驗收
			**風險**
			* 供應商延遲
			待確認事項：
			- 預算
		""".trimIndent()
		assertEquals(
			listOf(
				OutlineNode("重點", listOf(OutlineNode("會議開場說明"))),
				OutlineNode("決議", listOf(OutlineNode("採用 A 方案"))),
				OutlineNode("時程", listOf(OutlineNode("下週三交付"), OutlineNode("月底驗收"))),
				OutlineNode("風險", listOf(OutlineNode("供應商延遲"))),
				OutlineNode("待確認事項", listOf(OutlineNode("預算"))),
			),
			SummaryOutline.local(summary),
		)
	}

	@Test
	fun empty_or_placeholder_summary_has_no_outline() {
		assertTrue(SummaryOutline.local(null).isEmpty())
		assertTrue(SummaryOutline.local("  ").isEmpty())
		assertTrue(SummaryOutline.local("目前無重點").isEmpty())
		assertTrue(SummaryOutline.isEmptySummary("目前無重點。"))
	}

	@Test
	fun ai_outline_is_parsed_from_fenced_json() {
		val answer = "```json\n[{\"title\":\"決議\",\"children\":[{\"title\":\"採用 A\"}]},{\"title\":\"時程\"}]\n```"
		assertEquals(
			listOf(OutlineNode("決議", listOf(OutlineNode("採用 A"))), OutlineNode("時程")),
			SummaryOutline.parseAi(answer),
		)
	}

	@Test
	fun invalid_ai_outlines_are_rejected() {
		listOf(
			"不是 JSON",
			"[]",
			"[{\"title\":\"\"}]",
			"[{\"title\":\"a\",\"children\":[{\"title\":\"b\",\"children\":[{\"title\":\"c\"}]}]}]", // 3 levels
			"[{\"title\":\"a\",\"children\":\"b\"}]",
			"[{\"title\":\"a\",\"time\":\"00:01\"}]",
			"[\"a\"]",
			"[" + List(21) { "{\"title\":\"t$it\"}" }.joinToString(",") + "]",
			"[{\"title\":\"root\",\"children\":[" + List(20) { "{\"title\":\"c$it\"}" }.joinToString(",") + "]}]", // 21 nodes
		).forEach { assertNull(it, SummaryOutline.parseAi(it)) }
	}

	@Test
	fun prompt_sends_only_the_summary_and_hash_tracks_changes() {
		val prompt = SummaryOutline.prompt("- 決定 A")
		assertTrue(prompt.endsWith("摘要：\n- 決定 A"))
		assertTrue(prompt.contains("最多兩層") && prompt.contains("${SummaryOutline.MAX_NODES}"))
		assertEquals(SummaryOutline.hash("- 決定 A"), SummaryOutline.hash(" - 決定 A \n"))
		assertNotEquals(SummaryOutline.hash("- 決定 A"), SummaryOutline.hash("- 決定 B"))
	}

	@Test
	fun outline_text_for_copy() {
		assertEquals("決議\n  - 採用 A\n時程", SummaryOutline.asText(listOf(OutlineNode("決議", listOf(OutlineNode("採用 A"))), OutlineNode("時程"))))
	}
}

class OutlineStoreTests {
	@Test
	fun outline_round_trips_and_deletes() {
		val dir = kotlin.io.path.createTempDirectory("outline").toFile()
		try {
			val store = com.eva.cloudtranscribe.store.TranscriptStore(dir)
			assertNull(store.outline(3))
			val saved = SavedOutline("s1", SummaryOutline.hash("x"), listOf(OutlineNode("a", listOf(OutlineNode("b")))), 5, "m")
			store.saveOutline(3, saved)
			assertEquals(saved, store.outline(3))
			assertNull(store.outline(4))
			store.deleteOutline(3)
			assertNull(store.outline(3))
		} finally {
			dir.deleteRecursively()
		}
	}
}
