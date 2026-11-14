package com.eva.feature_player.cloud

import com.eva.cloudtranscribe.outline.OutlineNode
import com.eva.cloudtranscribe.outline.SummaryOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineRulesTest {

	private val ai = listOf(OutlineNode("決議", listOf(OutlineNode("採用 A"))))

	@Test
	fun stale_when_summary_changes_or_is_replaced() {
		val saved = OutlineRules.save("s1", "- 決定 A", ai, 1, "m")
		assertFalse(OutlineRules.isStale(null, "s1", "- 決定 A"))
		assertFalse(OutlineRules.isStale(saved, "s1", "- 決定 A"))
		assertTrue(OutlineRules.isStale(saved, "s1", "- 決定 A\n- 新增"))
		assertTrue(OutlineRules.isStale(saved, "s2", "- 決定 A"))
		assertNull(OutlineRules.save("s1", "x", ai, 1, " ").model)
	}

	@Test
	fun display_uses_ai_only_when_selected_and_available() {
		val saved = OutlineRules.save("s1", "- 決定 A", ai, 1, null)
		assertEquals(ai, OutlineRules.display(OutlineMode.AI, saved, "- 決定 A"))
		assertEquals(SummaryOutline.local("- 決定 A"), OutlineRules.display(OutlineMode.LOCAL, saved, "- 決定 A"))
		assertEquals(SummaryOutline.local("- 決定 A"), OutlineRules.display(OutlineMode.AI, null, "- 決定 A"))
	}
}
