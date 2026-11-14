package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.slack.SlackDeliveryRules
import com.eva.cloudtranscribe.slack.SlackDeliveryStateHolder
import com.eva.cloudtranscribe.slack.SlackHttpResponse
import com.eva.cloudtranscribe.slack.SlackMessageKind
import com.eva.cloudtranscribe.slack.SlackMessages
import com.eva.cloudtranscribe.slack.SlackOutbox
import com.eva.cloudtranscribe.slack.SlackOutgoing
import com.eva.cloudtranscribe.slack.SlackSendPolicy
import com.eva.cloudtranscribe.slack.SlackSendResult
import com.eva.cloudtranscribe.slack.SlackSender
import com.eva.cloudtranscribe.slack.SlackSyncCoordinator
import com.eva.cloudtranscribe.slack.SlackSyncSettings
import com.eva.cloudtranscribe.slack.SlackThrottle
import com.eva.cloudtranscribe.slack.SlackTransport
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** All Slack tests use fakes; nothing here touches the network. */
class SlackMessagesTests {

	@Test
	fun bullets_ignore_placeholder_and_duplicates() {
		assertEquals(emptyList<String>(), SlackMessages.bullets("目前無重點"))
		assertEquals(emptyList<String>(), SlackMessages.bullets("  "))
		assertEquals(listOf("決定 A", "B 負責報價"), SlackMessages.bullets("- 決定 A\n- 決定 A。\n• B 負責報價\n"))
	}

	@Test
	fun diff_returns_only_new_items() {
		val sent = listOf("決定 A", "B 負責報價")
		assertEquals(listOf("下週三再開會"), SlackMessages.diff(sent, listOf("決定 a。", "B  負責報價", "下週三再開會")))
		assertTrue(SlackMessages.diff(sent, sent).isEmpty())
	}

	@Test
	fun end_text_is_null_without_items_and_texts_are_capped() {
		assertNull(SlackMessages.endText(emptyList()))
		val long = SlackMessages.keyPointsText(List(400) { "很長的重點 $it ".repeat(3) })
		assertTrue(long.length <= SlackMessages.MAX_TEXT)
		assertTrue(long.endsWith(SlackMessages.TRUNCATED_MARK))
	}

	@Test
	fun start_text_has_zone_and_offset() {
		val text = SlackMessages.startText(0L, ZoneId.of("Asia/Taipei"))
		assertTrue(text, text.contains("1970-01-01 08:00") && text.contains("Asia/Taipei UTC+08:00"))
	}

	@Test
	fun mask_hides_emails_and_long_numbers() {
		val masked = SlackMessages.mask("寄到 a.b@example.com，電話 0912-345-678，第 3 季")
		assertFalse(masked.contains("example.com"))
		assertFalse(masked.contains("345"))
		assertTrue(masked.contains("第 3 季"))
	}

	@Test
	fun payload_is_escaped_json() {
		assertEquals("{\"text\":\"a \\\"b\\\"\\nc\"}", SlackMessages.payload("a \"b\"\nc"))
	}

	@Test
	fun only_slack_incoming_webhooks_are_valid() {
		assertTrue(SlackMessages.isValidWebhookUrl("https://hooks.slack.com/services/T000/B000/XXXXXXXX"))
		assertFalse(SlackMessages.isValidWebhookUrl("http://hooks.slack.com/services/T000/B000/XXXXXXXX"))
		assertFalse(SlackMessages.isValidWebhookUrl("https://example.com/services/T000/B000/XXXXXXXX"))
		assertFalse(SlackMessages.isValidWebhookUrl("https://hooks.slack.com/services/"))
	}

	@Test
	fun classify_follows_slack_errors() {
		assertEquals(SlackSendResult.Sent, SlackSendPolicy.classify(200, "ok", null))
		assertEquals(SlackSendResult.RetryAfter(7), SlackSendPolicy.classify(429, "", "7"))
		assertEquals(SlackSendResult.RetryAfter(SlackSendPolicy.DEFAULT_RETRY_AFTER_SECONDS), SlackSendPolicy.classify(429, "", null))
		assertEquals(SlackSendResult.Permanent("invalid_payload"), SlackSendPolicy.classify(400, "invalid_payload", null))
		assertEquals(SlackSendResult.Permanent("no_service"), SlackSendPolicy.classify(404, "no_service", null))
		assertEquals(SlackSendResult.Permanent("channel_is_archived"), SlackSendPolicy.classify(410, "channel_is_archived", null))
		assertTrue(SlackSendPolicy.classify(500, "rollup_error", null) is SlackSendResult.Retry)
	}

	@Test
	fun throttle_waits_for_interval() {
		assertEquals(0L, SlackThrottle.delayUntilDue(null, 1_000L, 180_000L))
		assertEquals(60_000L, SlackThrottle.delayUntilDue(0L, 120_000L, 180_000L))
		assertEquals(120_000L, SlackThrottle.intervalMillis(1))
		assertEquals(600_000L, SlackThrottle.intervalMillis(99))
	}

	@Test
	fun delivery_rules_isolate_sessions_and_respect_opt_out() {
		val on = SlackSyncSettings(enabled = true, consentAtMillis = 1L)
		val old = SlackOutgoing("s1", SlackMessageKind.KEY_POINTS, "x")
		assertFalse(SlackDeliveryRules.shouldDeliver(old, on, true, "s2", false))
		assertTrue(SlackDeliveryRules.shouldDeliver(old, on, true, "s1", false))
		// end message of an earlier recording still goes out after a new one started
		assertTrue(SlackDeliveryRules.shouldDeliver(old.copy(kind = SlackMessageKind.END), on, true, "s2", false))
		assertFalse(SlackDeliveryRules.shouldDeliver(old, SlackSyncSettings(enabled = true), true, "s1", false))
		assertFalse(SlackDeliveryRules.shouldDeliver(old, on, false, "s1", false))
		assertFalse(SlackDeliveryRules.shouldDeliver(old, on, true, "s1", alreadySent = true))
	}
}

class SlackSenderTests {

	@Test
	fun spaces_posts_and_retries_once_after_retry_after() = runTest {
		var now = 0L
		val waits = mutableListOf<Long>()
		val responses = ArrayDeque(listOf(SlackHttpResponse(429, "", "2"), SlackHttpResponse(200, "ok", null), SlackHttpResponse(200, "ok", null)))
		var posts = 0
		val sender = SlackSender(
			transport = SlackTransport { _, _ -> posts++; responses.removeFirst() },
			clock = { now },
			sleep = { waits += it; now += it },
		)
		assertEquals(SlackSendResult.Sent, sender.send(URL, "a"))
		assertEquals(2, posts)
		assertEquals(SlackSendResult.Sent, sender.send(URL, "b"))
		// 2s Retry-After wait, then the 1.1s spacing before the next post
		assertEquals(listOf(2_000L, 1_100L), waits.filter { it > 0 }.let { listOf(it.first(), it.last()) })
	}

	@Test
	fun network_error_is_retryable_and_long_retry_after_is_not_waited_inline() = runTest {
		val failing = SlackSender(SlackTransport { _, _ -> throw IOException("offline") }, sleep = {})
		assertTrue(failing.send(URL, "a") is SlackSendResult.Retry)
		val limited = SlackSender(SlackTransport { _, _ -> SlackHttpResponse(429, "", "300") }, sleep = {})
		assertEquals(SlackSendResult.RetryAfter(300), limited.send(URL, "a"))
	}

	private companion object {
		const val URL = "https://hooks.slack.com/services/T000/B000/XXXXXXXX"
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
class SlackSyncCoordinatorTests {

	private class FakeOutbox : SlackOutbox {
		val sent = mutableListOf<SlackOutgoing>()
		override fun enqueue(message: SlackOutgoing) { sent += message }
	}

	private class Holder : SlackDeliveryStateHolder {
		override var currentSessionId: String? = null
	}

	private val active = SlackSyncSettings(enabled = true, consentAtMillis = 1L, channelLabel = "#notes", minIntervalMinutes = 3)

	private fun TestScope.coordinator(
		outbox: FakeOutbox,
		settings: SlackSyncSettings = active,
		hasWebhook: Boolean = true,
		holder: Holder = Holder(),
	) = SlackSyncCoordinator(
		hasWebhook = { hasWebhook },
		outbox = outbox,
		deliveryState = holder,
		clock = { testScheduler.currentTime },
		scope = backgroundScope,
		settingsProvider = { settings },
	)

	@Test
	fun nothing_is_sent_without_opt_in_or_webhook() = runTest {
		val outbox = FakeOutbox()
		listOf(
			coordinator(outbox, settings = SlackSyncSettings()),
			coordinator(outbox, settings = SlackSyncSettings(enabled = true)), // no consent
			coordinator(outbox, hasWebhook = false),
		).forEach { c ->
			c.onRecordingStarted()
			c.onKeyPoints("- 決定 A")
			runCurrent()
			c.onRecordingSaved()
			assertFalse(c.status.value.syncing)
		}
		assertTrue(outbox.sent.isEmpty())
	}

	@Test
	fun start_then_throttled_coalesced_new_items_then_end() = runTest {
		val outbox = FakeOutbox()
		val c = coordinator(outbox)
		c.onRecordingStarted()
		assertTrue(c.status.value.syncing)
		assertEquals("#notes", c.status.value.channelLabel)
		assertEquals(SlackMessageKind.START, outbox.sent.single().kind)

		c.onKeyPoints("目前無重點")
		runCurrent()
		assertEquals(1, outbox.sent.size)

		c.onKeyPoints("- 決定 A")
		runCurrent()
		assertEquals("📝 新重點：\n• 決定 A\n（完整清單以 App 為準）", outbox.sent.last().text)

		// two updates inside the 3 min window are coalesced into one message with only new items
		advanceTimeBy(30_000)
		c.onKeyPoints("- 決定 A\n- B 負責報價")
		advanceTimeBy(30_000)
		c.onKeyPoints("- 決定 A\n- B 負責報價\n- 週三再開會")
		// placeholder later must not wipe anything
		c.onKeyPoints("目前無重點")
		runCurrent()
		assertEquals(2, outbox.sent.size)
		advanceTimeBy(120_001)
		runCurrent()
		assertEquals(3, outbox.sent.size)
		val update = outbox.sent.last().text
		assertTrue(update.contains("B 負責報價") && update.contains("週三再開會") && !update.contains("決定 A"))

		c.onRecordingSaved()
		val end = outbox.sent.last()
		assertEquals(SlackMessageKind.END, end.kind)
		assertTrue(end.text.contains("決定 A") && end.text.contains("週三再開會"))
		assertFalse(c.status.value.syncing)
		assertEquals(1, outbox.sent.map { it.sessionId }.distinct().size)
	}

	@Test
	fun pause_holds_updates_and_per_recording_switch_stops_sending() = runTest {
		val outbox = FakeOutbox()
		val c = coordinator(outbox)
		c.onRecordingStarted()
		c.onPaused()
		assertTrue(c.status.value.paused)
		c.onKeyPoints("- 決定 A")
		runCurrent()
		assertEquals(1, outbox.sent.size)
		c.onResumed()
		runCurrent()
		assertEquals(2, outbox.sent.size)

		c.setSyncForThisRecording(false)
		assertFalse(c.status.value.syncing)
		assertTrue(c.status.value.available)
		c.onKeyPoints("- 決定 A\n- 新的")
		advanceTimeBy(600_000)
		runCurrent()
		c.onRecordingSaved()
		assertEquals(2, outbox.sent.size)
	}

	@Test
	fun cancelled_recording_sends_no_end_and_new_session_gets_new_id() = runTest {
		val outbox = FakeOutbox()
		val holder = Holder()
		val c = coordinator(outbox, holder = holder)
		c.onRecordingStarted()
		val first = holder.currentSessionId
		c.onKeyPoints("- 決定 A")
		advanceTimeBy(10_000)
		c.onKeyPoints("- 決定 A\n- 尚未送出的")
		c.onRecordingCancelled()
		advanceTimeBy(600_000)
		runCurrent()
		assertEquals(listOf(SlackMessageKind.START, SlackMessageKind.KEY_POINTS), outbox.sent.map { it.kind })

		c.onRecordingStarted()
		assertTrue(holder.currentSessionId != null && holder.currentSessionId != first)
		assertEquals(holder.currentSessionId, outbox.sent.last().sessionId)
	}

	@Test
	fun end_is_skipped_when_there_are_no_key_points_and_masking_applies() = runTest {
		val outbox = FakeOutbox()
		val c = coordinator(outbox, settings = active.copy(sendStart = false, maskSensitive = true))
		c.onRecordingStarted()
		c.onRecordingSaved()
		assertTrue(outbox.sent.isEmpty())

		c.onRecordingStarted()
		c.onKeyPoints("- 聯絡 a@example.com")
		runCurrent()
		assertFalse(outbox.sent.single().text.contains("example.com"))
	}
}
