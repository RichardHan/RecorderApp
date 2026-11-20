package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.slack.SlackSessionPermit
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
		val long = SlackMessages.keyPointsText(List(3) { "很長的重點 $it ".repeat(2_000) })!!
		assertTrue(long.length <= SlackMessages.MAX_TEXT)
		assertTrue(long.endsWith(SlackMessages.TRUNCATED_MARK))
		assertFalse(long.contains("以 App 為準"))
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
		assertEquals(0, SlackThrottle.normalize(-3))
		assertEquals(5, SlackThrottle.normalize(3))
		assertEquals(5, SlackThrottle.normalize(7))
		assertEquals(10, SlackThrottle.normalize(8))
		assertEquals(15, SlackThrottle.normalize(99))
		assertEquals(5 * 60_000L, SlackThrottle.intervalMillis(3))
		assertEquals(15 * 60_000L, SlackThrottle.intervalMillis(99))
		assertEquals(0L, SlackThrottle.intervalMillis(0))
		assertTrue(SlackThrottle.isOff(0))
		assertEquals(Long.MAX_VALUE, SlackThrottle.delayUntilDue(null, 1_000L, 0L, sessionStartedAtMillis = 0L))
		assertEquals(300_000L, SlackThrottle.delayUntilDue(null, 0L, 300_000L, sessionStartedAtMillis = 0L))
		assertEquals(60_000L, SlackThrottle.delayUntilDue(0L, 120_000L, 180_000L))
		assertEquals(0L, SlackThrottle.delayUntilDue(null, 1_000L, 180_000L))
	}

	@Test
	fun delivery_rules_isolate_sessions_and_respect_opt_out() {
		val on = SlackSyncSettings(enabled = true, consentAtMillis = 1L)
		val old = SlackOutgoing("s1", SlackMessageKind.KEY_POINTS, "x", consentAtMillis = 1L)
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

	@Test
	fun revocation_during_retry_wait_prevents_second_post() = runTest {
		var allowed = true
		var posts = 0
		val sender = SlackSender(
			SlackTransport { _, _ -> posts++; SlackHttpResponse(429, "", "2") },
			sleep = { allowed = false },
		)
		assertEquals(SlackSendResult.Suppressed, sender.send(URL, "queued") { allowed })
		assertEquals(1, posts)
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

	private class FakeEndScheduler : com.eva.cloudtranscribe.slack.SlackEndScheduler {
		val requests = mutableListOf<com.eva.cloudtranscribe.slack.SlackEndRequest>()
		override fun schedule(request: com.eva.cloudtranscribe.slack.SlackEndRequest) { requests += request }
	}

	private class Holder : SlackDeliveryStateHolder {
		override var currentSessionId: String? = null
		private val permits = mutableMapOf<String, SlackSessionPermit>()
		override fun permit(id: String) = permits[id]
		override fun savePermit(id: String, permit: SlackSessionPermit) { permits[id] = permit }
	}

	private val active = SlackSyncSettings(enabled = true, consentAtMillis = 1L, channelLabel = "#notes", minIntervalMinutes = 5)

	private fun TestScope.coordinator(
		outbox: FakeOutbox,
		settings: SlackSyncSettings = active,
		hasWebhook: Boolean = true,
		holder: Holder = Holder(),
		endScheduler: FakeEndScheduler = FakeEndScheduler(),
	) = SlackSyncCoordinator(
		hasWebhook = { hasWebhook },
		outbox = outbox,
		deliveryState = holder,
		clock = { testScheduler.currentTime },
		scope = backgroundScope,
		settingsProvider = { settings },
		endScheduler = endScheduler,
	)

	@Test
	fun queued_messages_stay_revoked_after_reenable_and_cancel() = runTest {
		val outbox = FakeOutbox()
		val holder = Holder()
		val c = coordinator(outbox, holder = holder)
		c.onRecordingStarted()
		val queued = outbox.sent.single()
		fun allowed() = SlackDeliveryRules.shouldDeliver(queued, active, true,
			holder.currentSessionId, false, holder.permit(queued.sessionId))
		assertTrue(allowed())
		c.onPaused()
		assertTrue(holder.permit(queued.sessionId)!!.paused)
		c.onResumed()
		assertFalse(holder.permit(queued.sessionId)!!.paused)
		c.setSyncForThisRecording(false)
		assertFalse(allowed())
		c.setSyncForThisRecording(true)
		assertFalse(allowed())
		c.onRecordingCancelled()
		assertFalse(holder.permit(queued.sessionId)!!.enabled)
		assertTrue(holder.permit(queued.sessionId)!!.ended)
	}

	@Test
	fun saved_session_only_allows_end_and_requires_original_consent() = runTest {
		val outbox = FakeOutbox()
		val holder = Holder()
		val ends = FakeEndScheduler()
		val c = coordinator(outbox, holder = holder, endScheduler = ends)
		c.onRecordingStarted()
		c.onKeyPoints("- 決定 A")
		runCurrent()
		c.onRecordingSaved()
		outbox.sent.forEach { message ->
			assertTrue(message.kind != SlackMessageKind.END)
			assertFalse(SlackDeliveryRules.shouldDeliver(
				message, active, true, holder.currentSessionId, false, holder.permit(message.sessionId)))
			assertFalse(SlackDeliveryRules.shouldDeliver(message, active.copy(consentAtMillis = 2L),
				true, holder.currentSessionId, false, holder.permit(message.sessionId)))
			assertFalse(SlackDeliveryRules.shouldDeliver(message, active, true, holder.currentSessionId, false, null))
		}
		val req = ends.requests.single()
		assertEquals(0, req.generation)
		assertEquals(1L, req.consentAtMillis)
		val end = SlackOutgoing(req.sessionId, SlackMessageKind.END, "結束", req.generation, req.consentAtMillis)
		assertTrue(SlackDeliveryRules.shouldDeliver(
			end, active, true, holder.currentSessionId, false, holder.permit(end.sessionId)))
		assertFalse(SlackDeliveryRules.shouldDeliver(end, active.copy(consentAtMillis = 2L),
			true, holder.currentSessionId, false, holder.permit(end.sessionId)))
		assertFalse(SlackDeliveryRules.shouldDeliver(end, active, true, holder.currentSessionId, false, null))
	}

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
	fun start_then_throttled_coalesced_new_items_then_end_is_scheduled() = runTest {
		val outbox = FakeOutbox()
		val ends = FakeEndScheduler()
		val c = coordinator(outbox, endScheduler = ends)
		c.onRecordingStarted()
		assertTrue(c.status.value.syncing)
		assertEquals("#notes", c.status.value.channelLabel)
		assertEquals(SlackMessageKind.START, outbox.sent.single().kind)

		c.onKeyPoints("目前無重點")
		runCurrent()
		assertEquals(1, outbox.sent.size)

		// first mid-recording update also waits the full interval from session start
		c.onKeyPoints("- 決定 A")
		runCurrent()
		assertEquals(1, outbox.sent.size)

		advanceTimeBy(30_000)
		c.onKeyPoints("- 決定 A\n- B 負責報價")
		advanceTimeBy(30_000)
		c.onKeyPoints("- 決定 A\n- B 負責報價\n- 週三再開會")
		c.onKeyPoints("目前無重點")
		runCurrent()
		assertEquals(1, outbox.sent.size)
		advanceTimeBy(5 * 60_000L - 60_000L + 1)
		runCurrent()
		assertEquals(2, outbox.sent.size)
		assertEquals("📝 新重點：\n• 決定 A\n• B 負責報價\n• 週三再開會", outbox.sent.last().text)
		assertFalse(outbox.sent.last().text.contains("以 App 為準"))

		// second window: at most 3 new bullets, no extra compression
		c.onKeyPoints("- 決定 A\n- B 負責報價\n- 週三再開會\n- 第四\n- 第五\n- 第六")
		runCurrent()
		assertEquals(2, outbox.sent.size)
		advanceTimeBy(5 * 60_000L + 1)
		runCurrent()
		assertEquals(3, outbox.sent.size)
		val second = outbox.sent.last().text
		assertTrue(second.contains("第四") && second.contains("第五") && second.contains("第六"))
		assertFalse(second.contains("決定 A"))

		c.onRecordingSaved(recordingId = 42L, durationMs = 65_000L, title = "週會")
		assertTrue(outbox.sent.none { it.kind == SlackMessageKind.END })
		assertEquals(1, ends.requests.size)
		assertEquals(42L, ends.requests.single().recordingId)
		assertEquals(65_000L, ends.requests.single().durationMs)
		assertEquals("週會", ends.requests.single().title)
		assertEquals(0, ends.requests.single().generation)
		assertEquals(1L, ends.requests.single().consentAtMillis)
		assertTrue(ends.requests.single().liveKeyPoints.contains("決定 A"))
		assertTrue(ends.requests.single().liveKeyPoints.contains("第六"))
		assertFalse(c.status.value.syncing)
		assertEquals(1, outbox.sent.map { it.sessionId }.distinct().size)

		// a second save after the session ended must not schedule again
		c.onRecordingSaved(recordingId = 42L, durationMs = 65_000L)
		assertEquals(1, ends.requests.size)
	}

	@Test
	fun pause_holds_updates_and_per_recording_switch_stops_sending() = runTest {
		val outbox = FakeOutbox()
		val ends = FakeEndScheduler()
		val c = coordinator(outbox, endScheduler = ends)
		c.onRecordingStarted()
		c.onPaused()
		assertTrue(c.status.value.paused)
		c.onKeyPoints("- 決定 A")
		runCurrent()
		assertEquals(1, outbox.sent.size)
		c.onResumed()
		runCurrent()
		assertEquals(1, outbox.sent.size)
		advanceTimeBy(5 * 60_000L + 1)
		runCurrent()
		assertEquals(2, outbox.sent.size)

		c.setSyncForThisRecording(false)
		assertFalse(c.status.value.syncing)
		assertTrue(c.status.value.available)
		c.onKeyPoints("- 決定 A\n- 新的")
		advanceTimeBy(600_000)
		runCurrent()
		c.onRecordingSaved(recordingId = 1L)
		assertEquals(2, outbox.sent.size)
		assertTrue(ends.requests.isEmpty())
	}

	@Test
	fun cancelled_recording_sends_no_end_and_new_session_gets_new_id() = runTest {
		val outbox = FakeOutbox()
		val holder = Holder()
		val c = coordinator(outbox, holder = holder)
		c.onRecordingStarted()
		val first = holder.currentSessionId
		c.onKeyPoints("- 決定 A")
		advanceTimeBy(5 * 60_000L + 1)
		runCurrent()
		c.onKeyPoints("- 決定 A\n- 尚未送出的")
		c.onRecordingCancelled()
		advanceTimeBy(600_000)
		runCurrent()
		assertEquals(listOf(SlackMessageKind.START, SlackMessageKind.KEY_POINTS), outbox.sent.map { it.kind })
		assertFalse(outbox.sent.last().text.contains("尚未送出的"))

		c.onRecordingStarted()
		assertTrue(holder.currentSessionId != null && holder.currentSessionId != first)
		assertEquals(holder.currentSessionId, outbox.sent.last().sessionId)
	}

	@Test
	fun end_is_scheduled_even_without_live_points_and_masking_applies() = runTest {
		val outbox = FakeOutbox()
		val ends = FakeEndScheduler()
		val c = coordinator(outbox, settings = active.copy(sendStart = false, maskSensitive = true), endScheduler = ends)
		c.onRecordingStarted()
		c.onRecordingSaved(recordingId = 9L, durationMs = 1_000L)
		assertTrue(outbox.sent.isEmpty())
		assertEquals(1, ends.requests.size)
		assertTrue(ends.requests.single().liveKeyPoints.isEmpty())
		assertTrue(ends.requests.single().maskSensitive)

		c.onRecordingStarted()
		c.onKeyPoints("- 聯絡 a@example.com")
		runCurrent()
		assertTrue(outbox.sent.isEmpty())
		advanceTimeBy(5 * 60_000L + 1)
		runCurrent()
		assertFalse(outbox.sent.single().text.contains("example.com"))
	}

	@Test
	fun mid_updates_are_disabled_when_interval_is_off() = runTest {
		val outbox = FakeOutbox()
		val c = coordinator(outbox, settings = active.copy(minIntervalMinutes = 0))
		c.onRecordingStarted()
		c.onKeyPoints("- 決定 A")
		advanceTimeBy(20 * 60_000L)
		runCurrent()
		assertEquals(listOf(SlackMessageKind.START), outbox.sent.map { it.kind })
	}
}
