package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.followup.FollowUpItem
import com.eva.cloudtranscribe.followup.FollowUpPlanner
import com.eva.cloudtranscribe.store.FollowUpDraft
import com.eva.cloudtranscribe.store.FollowUpDraftStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpPlannerTests {

	@Test
	fun `nothing accepted means no materials and so no request`() {
		assertNull(FollowUpPlanner.materials(null, emptyList()))
		assertNull(FollowUpPlanner.materials("   ", emptyList()))
		assertNull(FollowUpPlanner.materials("- 目前無重點", emptyList()))
		assertNull(FollowUpPlanner.materials("目前無重點", listOf(FollowUpItem("  ", false))))
	}

	@Test
	fun `summary or to-dos alone are enough`() {
		val fromSummary = FollowUpPlanner.materials("- 決定下週上線", emptyList())
		assertEquals("- 決定下週上線", fromSummary?.summary)
		val fromItems = FollowUpPlanner.materials(null, listOf(FollowUpItem("寄合約", false)))
		assertNull(fromItems?.summary)
		assertEquals(1, fromItems?.actionItems?.size)
	}

	@Test
	fun `long material is truncated and flagged`() {
		val long = "a".repeat(FollowUpPlanner.MAX_MATERIAL_CHARS + 50)
		val materials = FollowUpPlanner.materials(long, listOf(FollowUpItem("寄合約", false)))!!
		assertTrue(materials.truncated)
		assertEquals(FollowUpPlanner.MAX_MATERIAL_CHARS, materials.summary!!.length)
		assertTrue(materials.actionItems.isEmpty())
	}

	@Test
	fun `prompt forbids invented owners and dates and contains only the given text`() {
		val materials = FollowUpPlanner.materials("- 決定下週上線", listOf(FollowUpItem("寄合約", true)))!!
		val prompt = FollowUpPlanner.prompt(materials)
		assertTrue(prompt.contains("不得發明負責人、期限"))
		assertTrue(prompt.contains("- 決定下週上線"))
		assertTrue(prompt.contains("（已完成）寄合約"))
		assertFalse(prompt.contains("逐字稿全文"))
		// custom style replaces the default format but keeps the rules
		val custom = FollowUpPlanner.prompt(materials, "用條列，英文")
		assertTrue(custom.contains("用條列，英文"))
		assertTrue(custom.contains("不得發明負責人、期限"))
		assertFalse(custom.contains("不超過 200 字"))
	}

	@Test
	fun `empty or placeholder output is rejected so the old draft stays`() {
		assertNull(FollowUpPlanner.accept(null))
		assertNull(FollowUpPlanner.accept("  "))
		assertNull(FollowUpPlanner.accept("目前無重點"))
		assertNull(FollowUpPlanner.accept("```\n目前無重點\n```"))
		assertEquals("大家好，\n- 下週上線", FollowUpPlanner.accept("```text\n大家好，\n- 下週上線\n```"))
		assertEquals("謝謝大家", FollowUpPlanner.accept("  謝謝大家 \n"))
	}

	@Test
	fun `revision changes when summary or to-dos change`() {
		val a = FollowUpPlanner.materials("- A", listOf(FollowUpItem("x", false)))
		val b = FollowUpPlanner.materials("- A", listOf(FollowUpItem("x", true)))
		val c = FollowUpPlanner.materials("- B", listOf(FollowUpItem("x", false)))
		assertEquals(FollowUpPlanner.revisionOf("s1", a), FollowUpPlanner.revisionOf("s1", a))
		assertNotEquals(FollowUpPlanner.revisionOf("s1", a), FollowUpPlanner.revisionOf("s1", b))
		assertNotEquals(FollowUpPlanner.revisionOf("s1", a), FollowUpPlanner.revisionOf("s1", c))
		assertEquals("", FollowUpPlanner.revisionOf("s1", null))
	}

	@Test
	fun `draft store keeps one draft per recording`() {
		val root = Files.createTempDirectory("followup").toFile()
		try {
			val store = FollowUpDraftStore(root)
			assertNull(store.read(3L))
			store.save(FollowUpDraft(3L, "第一版", 1L, "r1"))
			store.save(FollowUpDraft(3L, "第二版", 2L, "r2"))
			val read = store.read(3L)
			assertNotNull(read)
			assertEquals("第二版", read!!.draftText)
			assertEquals("r2", read.sourceRevision)
			store.delete(3L)
			assertNull(store.read(3L))
		} finally {
			root.deleteRecursively()
		}
	}
}
