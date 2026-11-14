package com.eva.cloudtranscribe.slack

import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the recorder screen shows about Slack sync for the current recording. */
data class SlackSyncStatus(
	/** Sync is on for the current recording (global opt-in + webhook + not turned off for this one). */
	val syncing: Boolean = false,
	/** Global opt-in is on and a webhook exists, so the per-recording switch can be shown. */
	val available: Boolean = false,
	val channelLabel: String = "",
	val paused: Boolean = false,
)

/**
 * Turns recorder lifecycle + live key points into Slack messages. Opt-in only: nothing is queued unless the user
 * enabled sync, agreed in the consent dialog and saved a webhook. Only locally produced text is sent (no audio).
 *
 * v1 uses an incoming webhook, which cannot edit messages or reliably thread, so "continuous" means: one start
 * message, then a new message with only the NEW key points at most once per interval, then one end message.
 */
@Singleton
class SlackSyncCoordinator internal constructor(
	private val hasWebhook: () -> Boolean,
	private val outbox: SlackOutbox,
	private val deliveryState: SlackDeliveryStateHolder,
	private val clock: () -> Long,
	private val scope: CoroutineScope,
	private val settingsProvider: suspend () -> SlackSyncSettings,
) {

	@Inject
	constructor(
		settingsRepo: SlackSyncSettingsRepository,
		webhookStore: SlackWebhookStore,
		outbox: WorkManagerSlackOutbox,
		deliveryState: SlackDeliveryState,
	) : this(
		hasWebhook = { webhookStore.hasKey.value },
		outbox = outbox,
		deliveryState = object : SlackDeliveryStateHolder {
			override var currentSessionId: String?
				get() = deliveryState.currentSessionId
				set(value) { deliveryState.currentSessionId = value }
		},
		clock = { System.currentTimeMillis() },
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
		settingsProvider = { settingsRepo.current() },
	)

	private class Session(
		val id: String,
		val startedAtMillis: Long,
		val settings: SlackSyncSettings,
	) {
		var override: Boolean? = null
		var sentItems: List<String> = emptyList()
		var latestItems: List<String> = emptyList()
		var lastKeyPointsSentAt: Long? = null
		var paused = false
		var timer: Job? = null
	}

	private val lock = Any()
	private var session: Session? = null
	private val _status = MutableStateFlow(SlackSyncStatus())
	val status: StateFlow<SlackSyncStatus> = _status.asStateFlow()

	private fun Session.syncing(): Boolean = settings.isActive && hasWebhook() && override != false

	private fun publish(s: Session?) {
		_status.value = if (s == null) SlackSyncStatus() else SlackSyncStatus(
			syncing = s.syncing(),
			available = s.settings.isActive && hasWebhook(),
			channelLabel = s.settings.channelLabel,
			paused = s.paused,
		)
	}

	/** A new recording started: older recordings' pending key points can no longer be sent. */
	suspend fun onRecordingStarted() {
		val settings = runCatching { settingsProvider() }.getOrDefault(SlackSyncSettings())
		val now = clock()
		val next = Session(UUID.randomUUID().toString(), now, settings)
		synchronized(lock) {
			session?.timer?.cancel()
			session = next
		}
		deliveryState.currentSessionId = next.id
		publish(next)
		if (next.syncing() && settings.sendStart) {
			outbox.enqueue(SlackOutgoing(next.id, SlackMessageKind.START, SlackMessages.startText(now)))
		}
	}

	/** Per-recording switch on the recorder screen (only turning OFF/ON within an already agreed opt-in). */
	fun setSyncForThisRecording(enabled: Boolean) {
		val s = synchronized(lock) { session } ?: return
		s.override = enabled
		if (!enabled) {
			s.timer?.cancel()
			s.timer = null
		}
		publish(s)
	}

	fun onPaused() {
		val s = synchronized(lock) { session } ?: return
		s.paused = true
		publish(s)
	}

	fun onResumed() {
		val s = synchronized(lock) { session } ?: return
		s.paused = false
		publish(s)
		// an update that arrived just before the pause may now be due
		scheduleKeyPoints(s)
	}

	/** Latest accepted live key points (whole list); only new items are ever sent. */
	fun onKeyPoints(keyPoints: String?) {
		val s = synchronized(lock) { session } ?: return
		val items = SlackMessages.bullets(keyPoints)
		// "目前無重點" / blank never replaces what was already sent or queued
		if (items.isEmpty()) return
		synchronized(lock) { s.latestItems = items }
		scheduleKeyPoints(s)
	}

	private fun scheduleKeyPoints(s: Session) = synchronized(lock) {
		if (!s.syncing() || !s.settings.sendKeyPoints || s.paused) return@synchronized
		if (SlackMessages.diff(s.sentItems, s.latestItems).isEmpty()) return@synchronized
		if (s.timer?.isActive == true) return@synchronized // coalesced: the timer sends the newest diff when due
		val wait = SlackThrottle.delayUntilDue(
			s.lastKeyPointsSentAt, clock(), SlackThrottle.intervalMillis(s.settings.normalizedIntervalMinutes),
		)
		s.timer = scope.launch {
			if (wait > 0) delay(wait)
			flushKeyPoints(s)
		}
	}

	private fun flushKeyPoints(s: Session) {
		val message = synchronized(lock) {
			if (session !== s || !s.syncing() || s.paused) return
			val newItems = SlackMessages.diff(s.sentItems, s.latestItems)
			if (newItems.isEmpty()) return
			s.sentItems = s.sentItems + newItems
			s.lastKeyPointsSentAt = clock()
			s.timer = null
			SlackOutgoing(s.id, SlackMessageKind.KEY_POINTS, maybeMask(s, SlackMessages.keyPointsText(newItems)))
		}
		outbox.enqueue(message)
	}

	/** Recording saved: drop any pending mid-way update and send one end message (if there is anything to say). */
	fun onRecordingSaved() {
		val s = synchronized(lock) { session?.also { session = null } } ?: return
		s.timer?.cancel()
		publish(null)
		if (!s.syncing() || !s.settings.sendEnd) return
		val finalItems = s.latestItems.ifEmpty { s.sentItems }
		val text = SlackMessages.endText(finalItems) ?: return
		outbox.enqueue(SlackOutgoing(s.id, SlackMessageKind.END, maybeMask(s, text)))
	}

	/** Recording discarded: nothing more is sent for it. */
	fun onRecordingCancelled() {
		val s = synchronized(lock) { session?.also { session = null } } ?: return
		s.timer?.cancel()
		publish(null)
		Log.d(TAG, "SESSION CANCELLED, NOTHING MORE SENT")
	}

	private fun maybeMask(s: Session, text: String) = if (s.settings.maskSensitive) SlackMessages.mask(text) else text

	private companion object {
		const val TAG = "SLACK_SYNC"
	}
}

/** Indirection so the coordinator can be tested without SharedPreferences. */
interface SlackDeliveryStateHolder {
	var currentSessionId: String?
}
