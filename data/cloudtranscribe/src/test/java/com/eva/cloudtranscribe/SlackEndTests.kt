package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.engine.SpeakerOwnerPrompt
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.slack.SlackEndCondense
import com.eva.cloudtranscribe.slack.SlackEndMessage
import com.eva.cloudtranscribe.slack.SlackEndPolicy
import com.eva.cloudtranscribe.slack.SlackEndRequest
import com.eva.cloudtranscribe.slack.SlackEndScheduler
import com.eva.cloudtranscribe.slack.SlackSessionPermit
import com.eva.cloudtranscribe.slack.SlackSyncSettings
import com.eva.cloudtranscribe.slack.SlackMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatting, caps, fallback and name attribution — no network. */
class SlackEndMessageTests {

	@Test
	fun header_uses_title_and_duration() {
		assertEquals("✅ 週會 已結束（1:05）", SlackEndMessage.header("週會", 65_000L))
		assertEquals("✅ 錄音 已結束（1:02:03）", SlackEndMessage.header("  ", 3_723_000L))
		assertEquals("✅ 錄音 已結束", SlackEndMessage.header(null, 0L))
	}

	@Test
	fun format_omits_empty_sections_and_uses_placeholder_when_nothing() {
		val empty = SlackEndMessage.format("週會", 60_000L, null, emptyList(), emptyList())
		assertEquals("✅ 週會 已結束（1:00）\n\n目前無重點", empty)
		assertFalse(empty.contains("決議："))
		assertFalse(empty.contains("待辦："))

		val onlyTodos = SlackEndMessage.format(
			"週會",
			60_000L,
			abstract = "這次只確認時程。",
			decisions = emptyList(),
			todos = listOf("整理投影片 design（負責：Charlie）"),
		)
		assertTrue(onlyTodos.contains("摘要：\n這次只確認時程。"))
		assertFalse(onlyTodos.contains("決議："))
		assertTrue(onlyTodos.contains("待辦：\n• 整理投影片 design（負責：Charlie）"))
		assertFalse(onlyTodos.contains("以 App 為準"))
	}

	@Test
	fun format_caps_decisions_and_todos_at_five_and_preserves_names() {
		val named = "Hank：正處理 503 問題（負責：Hank）"
		val text = SlackEndMessage.format(
			"除錯",
			90_000L,
			abstract = "先修 503。",
			decisions = listOf(named) + (1..8).map { "決議 $it" },
			todos = listOf("待辦：整理投影片 design（負責：Charlie）") + (1..8).map { "事項 $it" },
		)
		assertTrue(text.contains("• $named"))
		assertEquals(5, text.substringAfter("決議：").substringBefore("待辦：").lines().count { it.startsWith("• ") })
		assertEquals(5, text.substringAfter("待辦：").lines().count { it.startsWith("• ") })
		assertTrue(text.contains("整理投影片 design（負責：Charlie）"))
		assertFalse(text.contains("決議 6"))
		assertFalse(text.contains("事項 6"))
	}

	@Test
	fun unnamed_bullets_stay_as_is_and_names_are_never_invented() {
		val parsed = SlackEndMessage.parse(
			"""
			摘要：今天只討論時程。
			重點：
			- 週三交報價
			- 下週再開會
			""".trimIndent(),
		)
		assertEquals("今天只討論時程。", parsed.abstract)
		assertEquals(listOf("週三交報價", "下週再開會"), parsed.points)
		assertTrue(parsed.decisions.isEmpty())
		assertTrue(parsed.todos.isEmpty())
		val text = SlackEndMessage.format("會", 1_000L, parsed.abstract, parsed.decisions, parsed.todos, parsed.points)
		assertTrue(text.contains("• 週三交報價"))
		assertFalse(text.contains("負責："))
		assertFalse(text.contains("Hank"))
		assertFalse(text.contains("Charlie"))
	}

	@Test
	fun parse_keeps_explicit_speaker_and_owner() {
		val parsed = SlackEndMessage.parse(
			"""
			摘要：Hank 說明 503 仍在處理。
			決議：
			- Hank：正處理 503 問題（負責：Hank）
			待辦：
			- 待辦：整理投影片 design（負責：Charlie）
			""".trimIndent(),
		)
		assertEquals("Hank 說明 503 仍在處理。", parsed.abstract)
		assertEquals(listOf("Hank：正處理 503 問題（負責：Hank）"), parsed.decisions)
		assertEquals(listOf("整理投影片 design（負責：Charlie）"), parsed.todos)
	}

	@Test
	fun compose_uses_summary_when_present_and_falls_back_to_capped_live_points() {
		val fromSummary = SlackEndMessage.compose(
			title = "週會",
			durationMs = 60_000L,
			summaryText = "摘要：進度正常。\n決議：\n- 採用方案 A\n待辦：\n- 寫文件（負責：Ann）",
			fallbackItems = listOf("不應該出現的舊重點"),
		)
		assertTrue(fromSummary.contains("摘要：\n進度正常。"))
		assertTrue(fromSummary.contains("• 採用方案 A"))
		assertTrue(fromSummary.contains("• 寫文件（負責：Ann）"))
		assertFalse(fromSummary.contains("不應該出現的舊重點"))

		val many = (1..12).map { "重點 $it" }
		val fallback = SlackEndMessage.compose("週會", 60_000L, summaryText = "目前無重點", fallbackItems = many)
		assertEquals(5, fallback.lines().count { it.startsWith("• ") })
		assertTrue(fallback.contains("• 重點 1") && fallback.contains("• 重點 5"))
		assertFalse(fallback.contains("重點 6"))

		val condensed = SlackEndMessage.compose(
			"週會",
			60_000L,
			summaryText = null,
			fallbackItems = many,
			condensedItems = listOf("Hank：正處理 503 問題（負責：Hank）", "週三交報價"),
		)
		assertTrue(condensed.contains("• Hank：正處理 503 問題（負責：Hank）"))
		assertTrue(condensed.contains("• 週三交報價"))
		assertFalse(condensed.contains("重點 1"))
	}

	@Test
	fun mid_update_caps_at_three_has_no_footer_and_preserves_names() {
		assertNull(SlackMessages.keyPointsText(emptyList()))
		val named = "Hank：正處理 503 問題（負責：Hank）"
		val text = SlackMessages.keyPointsText(listOf(named, "週三交報價", "再開會", "多餘的第四點"))
		assertEquals("📝 新重點：\n• $named\n• 週三交報價\n• 再開會", text)
		assertFalse(text!!.contains("第四點"))
		assertFalse(text.contains("以 App 為準"))
		assertEquals(
			"📝 新重點：\n• 週三交報價",
			SlackMessages.keyPointsText(listOf("週三交報價")),
		)
	}

	@Test
	fun cap_items_dedupes_and_leaves_unnamed_text_untouched() {
		assertEquals(listOf("決定 A", "B 負責報價", "第四"), SlackMessages.capItems(listOf("決定 A", "決定 a。", "B 負責報價", "第四"), 3))
		assertEquals(listOf("週三交報價"), SlackMessages.capItems(listOf("週三交報價"), 5))
	}

	@Test
	fun condense_prompt_forbids_guessing_names_and_parse_caps_replies() {
		val prompt = SlackEndCondense.prompt(listOf("Hank：正處理 503 問題（負責：Hank）", "週三交報價"))
		assertTrue(prompt.contains(SpeakerOwnerPrompt.RULE))
		assertTrue(prompt.contains("絕不可猜測"))
		assertTrue(prompt.contains("Hank：正處理 503 問題（負責：Hank）"))
		assertEquals(
			listOf("A", "B", "C", "D", "E"),
			SlackEndCondense.parseReply(listOf("A", "B", "C", "D", "E", "F", "G", "H").joinToString("\n") { "- $it" }),
		)
	}
}

class SlackEndPolicyTests {

	private fun summary(
		engine: String,
		status: RunStatus,
		text: String = "- 重點",
		created: Long = 10L,
		id: String = "s-$engine-$created",
	) = SummaryRecord(
		id = id,
		recordingId = 7L,
		sourceTranscriptId = "t-1",
		provenance = Provenance(engine = engine, model = "m"),
		createdAtMillis = created,
		status = status,
		text = text,
	)

	@Test
	fun prefers_latest_successful_post_recording_summary_and_ignores_live_keypoints() {
		assertNull(
			SlackEndPolicy.pickPostRecordingSummary(
				listOf(
					summary("live-keypoints", RunStatus.SUCCESS, "- 即時", 30L),
					summary("cloud-summary", RunStatus.FAILED, "- 失敗", 40L),
					summary("cloud-summary", RunStatus.SUCCESS, "  ", 50L),
				),
			),
		)
		val picked = SlackEndPolicy.pickPostRecordingSummary(
			listOf(
				summary("live-keypoints", RunStatus.SUCCESS, "- 即時", 90L),
				summary("cloud-summary", RunStatus.SUCCESS, "- 舊", 10L, "old"),
				summary("cloud-summary", RunStatus.SUCCESS, "- 新", 20L, "new"),
			),
		)
		assertEquals("new", picked?.id)
		assertEquals("- 新", picked?.text)
	}

	@Test
	fun waits_only_when_the_auto_pipeline_will_run_or_a_summary_is_in_flight() {
		assertTrue(SlackEndPolicy.shouldWaitForPipeline(true, true))
		assertFalse(SlackEndPolicy.shouldWaitForPipeline(true, false))
		assertFalse(SlackEndPolicy.shouldWaitForPipeline(false, true))

		val ready = summary("cloud-summary", RunStatus.SUCCESS, "- 好")
		assertEquals(
			SlackEndPolicy.Wait.READY,
			SlackEndPolicy.decide(1_000L, 0L, waitForPipeline = true, summary = ready, inFlight = false),
		)
		assertEquals(
			SlackEndPolicy.Wait.WAIT,
			SlackEndPolicy.decide(1_000L, 0L, waitForPipeline = true, summary = null, inFlight = false),
		)
		assertEquals(
			SlackEndPolicy.Wait.WAIT,
			SlackEndPolicy.decide(1_000L, 0L, waitForPipeline = false, summary = null, inFlight = true),
		)
		assertEquals(
			SlackEndPolicy.Wait.FALLBACK,
			SlackEndPolicy.decide(1_000L, 0L, waitForPipeline = false, summary = null, inFlight = false),
		)
		assertEquals(
			SlackEndPolicy.Wait.FALLBACK,
			SlackEndPolicy.decide(SlackEndPolicy.TIMEOUT_MS + 1, 0L, waitForPipeline = true, summary = null, inFlight = true),
		)
		assertTrue(SlackEndPolicy.isInFlight(listOf(summary("cloud-summary", RunStatus.QUEUED, "", 1L))))
		assertFalse(SlackEndPolicy.isInFlight(listOf(summary("live-keypoints", RunStatus.RUNNING, "", 1L))))
	}

	@Test
	fun unique_work_name_is_stable_per_session_so_a_second_schedule_is_idempotent() {
		assertEquals("slack_end_abc", SlackEndScheduler.uniqueName("abc"))
		assertEquals(SlackEndScheduler.uniqueName("abc"), SlackEndScheduler.uniqueName("abc"))
		assertTrue(SlackEndScheduler.uniqueName("a") != SlackEndScheduler.uniqueName("b"))
		val req = SlackEndRequest("abc", 7L, 1_000L, "週會", listOf("x"), false, 0L)
		assertEquals("abc", req.sessionId)
		assertEquals(7L, req.recordingId)
	}
	@Test
	fun canProceed_blocks_revoked_consent_missing_webhook_disabled_sendEnd_and_invalid_permit() {
		val active = SlackSyncSettings(enabled = true, consentAtMillis = 100L, sendEnd = true)
		val permit = SlackSessionPermit(generation = 0, enabled = true, paused = false, ended = true)
		assertTrue(SlackEndPolicy.canProceed(active, hasWebhook = true, consentAtMillis = 100L, generation = 0, permit = permit))
		assertFalse(SlackEndPolicy.canProceed(active, hasWebhook = false, consentAtMillis = 100L, generation = 0, permit = permit))
		assertFalse(SlackEndPolicy.canProceed(active.copy(enabled = false), hasWebhook = true, consentAtMillis = 100L, generation = 0, permit = permit))
		assertFalse(SlackEndPolicy.canProceed(active.copy(consentAtMillis = null), hasWebhook = true, consentAtMillis = 100L, generation = 0, permit = permit))
		assertFalse(SlackEndPolicy.canProceed(active.copy(consentAtMillis = 200L), hasWebhook = true, consentAtMillis = 100L, generation = 0, permit = permit))
		assertFalse(SlackEndPolicy.canProceed(active.copy(sendEnd = false), hasWebhook = true, consentAtMillis = 100L, generation = 0, permit = permit))
		assertFalse(SlackEndPolicy.canProceed(active, hasWebhook = true, consentAtMillis = 100L, generation = 0, permit = null))
		assertFalse(SlackEndPolicy.canProceed(active, hasWebhook = true, consentAtMillis = 100L, generation = 0, permit = permit.copy(enabled = false)))
		assertFalse(SlackEndPolicy.canProceed(active, hasWebhook = true, consentAtMillis = 100L, generation = 0, permit = permit.copy(generation = 1)))
	}
}
