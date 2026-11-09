package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.model.SummaryActionItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning an existing summary into to-do candidates: bullets only, no invented items, no duplicates. */
class SummaryActionItemsTests {

	private val summary = """
		決議
		- 下週三前完成報價單
		- 找 Amy 確認折扣
		• 這是項目符號但不是我們的格式
		這段是說明文字，不是待辦
		- 下週三前完成報價單
		-  找  Amy 確認折扣
	""".trimIndent()

	@Test
	fun onlyBulletLinesBecomeCandidatesAndDuplicatesCollapse() {
		val items = SummaryActionItems.candidates(summary)
		assertEquals(listOf("下週三前完成報價單", "找 Amy 確認折扣"), items)
	}

	@Test
	fun noPointsPlaceholderProducesNoItems() {
		assertTrue(SummaryActionItems.candidates("目前無重點").isEmpty())
		assertTrue(SummaryActionItems.candidates("- 目前無重點").isEmpty())
		assertTrue(SummaryActionItems.candidates("（尚無）").isEmpty())
		assertTrue(SummaryActionItems.candidates("").isEmpty())
	}

	@Test
	fun blankBulletsAreIgnored() {
		assertTrue(SummaryActionItems.candidates("- \n-   \n").isEmpty())
	}

	@Test
	fun normalizationIgnoresCaseAndInnerWhitespace() {
		assertEquals(SummaryActionItems.normalize("  Call  AMY "), SummaryActionItems.normalize("call amy"))
		assertTrue(SummaryActionItems.isDuplicate("Call AMY", listOf("call  amy")))
		assertFalse(SummaryActionItems.isDuplicate("Call Bob", listOf("call amy")))
		assertFalse(SummaryActionItems.isDuplicate("   ", listOf("call amy")))
	}

	@Test
	fun clipboardContainsOnlyUnfinishedItemsInFixedFormat() {
		val text = SummaryActionItems.unfinishedAsClipboardText(
			listOf("寫報價單" to false, "寄出" to true, "約會議" to false),
		)
		assertEquals("- [ ] 寫報價單\n- [ ] 約會議", text)
		assertEquals("", SummaryActionItems.unfinishedAsClipboardText(emptyList()))
		assertEquals("", SummaryActionItems.unfinishedAsClipboardText(listOf("寄出" to true)))
	}
}
