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
	private val endScheduler: SlackEndScheduler = NoOpSlackEndScheduler,
) {

	@Inject
	constructor(
		settingsRepo: SlackSyncSettingsRepository,
		webhookStore: SlackWebhookStore,
		outbox: WorkManagerSlackOutbox,
		deliveryState: SlackDeliveryState,
		endScheduler: WorkManagerSlackEndScheduler,
	) : this(
		hasWebhook = { webhookStore.hasKey.value },
		outbox = outbox,
		deliveryState = object : SlackDeliveryStateHolder {
			override var currentSessionId: String?
				get() = deliveryState.currentSessionId
				set(value) { deliveryState.currentSessionId = value }
			override fun permit(id: String) = deliveryState.permit(id)
			override fun savePermit(id: String, permit: SlackSessionPermit) = deliveryState.savePermit(id, permit)
		},
		clock = { System.currentTimeMillis() },
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
		settingsProvider = { settingsRepo.current() },
		endScheduler = endScheduler,
	)

	private class Session(
		val id: String,
		val startedAtMillis: Long,
		val settings: SlackSyncSettings,
	) {
		var override: Boolean? = null
		var generation: Int = 0
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
		deliveryState.savePermit(next.id, SlackSessionPermit(enabled = next.syncing()))
		deliveryState.currentSessionId = next.id
		publish(next)
		if (next.syncing() && settings.sendStart) {
			outbox.enqueue(outgoing(next, SlackMessageKind.START, SlackMessages.startText(now)))
		}
	}

	/** Per-recording switch on the recorder screen (only turning OFF/ON within an already agreed opt-in). */
	fun setSyncForThisRecording(enabled: Boolean) {
		val s = synchronized(lock) { session } ?: return
		s.override = enabled
		// Revocation invalidates every previously queued message, even after re-enabling this recording.
		if (!enabled) s.generation++
		persist(s)
		if (!enabled) {
			s.timer?.cancel()
			s.timer = null
		}
		publish(s)
	}

	fun onPaused() {
		val s = synchronized(lock) { session } ?: return
		s.paused = true
		persist(s)
		publish(s)
	}

	fun onResumed() {
		val s = synchronized(lock) { session } ?: return
		s.paused = false
		persist(s)
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
		if (SlackThrottle.isOff(s.settings.normalizedIntervalMinutes)) return@synchronized
		if (SlackMessages.diff(s.sentItems, s.latestItems).isEmpty()) return@synchronized
		if (s.timer?.isActive == true) return@synchronized // coalesced: the timer sends the newest diff when due
		val wait = SlackThrottle.delayUntilDue(
			s.lastKeyPointsSentAt,
			clock(),
			SlackThrottle.intervalMillis(s.settings.normalizedIntervalMinutes),
			s.startedAtMillis,
		)
		s.timer = scope.launch {
			if (wait > 0) delay(wait)
			flushKeyPoints(s)
		}
	}

	private fun flushKeyPoints(s: Session) {
		val message = synchronized(lock) {
			if (session !== s || !s.syncing() || s.paused) return
			val newItems = SlackMessages.capItems(
				SlackMessages.diff(s.sentItems, s.latestItems),
				SlackMessages.MID_MAX_ITEMS,
			)
			if (newItems.isEmpty()) return
			s.sentItems = s.sentItems + newItems
			s.lastKeyPointsSentAt = clock()
			s.timer = null
			val text = SlackMessages.keyPointsText(newItems) ?: return
			outgoing(s, SlackMessageKind.KEY_POINTS, maybeMask(s, text))
		}
		outbox.enqueue(message)
	}

	/**
	 * Recording saved: drop any pending mid-way update and schedule one end message that waits for the
	 * post-recording cloud summary (with a live-key-point fallback). Never mixes sessions.
	 */
	fun onRecordingSaved(recordingId: Long = 0L, durationMs: Long = 0L, title: String? = null) {
		val s = synchronized(lock) { session?.also { session = null } } ?: return
		s.timer?.cancel()
		persist(s, ended = true)
		publish(null)
		if (!s.syncing() || !s.settings.sendEnd) return
		endScheduler.schedule(
			SlackEndRequest(
				sessionId = s.id,
				recordingId = recordingId,
				durationMs = durationMs,
				title = title,
				liveKeyPoints = s.latestItems.ifEmpty { s.sentItems },
				maskSensitive = s.settings.maskSensitive,
				enqueuedAtMillis = clock(),
				generation = s.generation,
				consentAtMillis = s.settings.consentAtMillis,
			),
		)
	}

	/** Recording discarded: nothing more is sent for it. */
	fun onRecordingCancelled() {
		val s = synchronized(lock) { session?.also { session = null } } ?: return
		s.timer?.cancel()
		s.override = false
		s.generation++
		persist(s, ended = true)
		publish(null)
		Log.d(TAG, "SESSION CANCELLED, NOTHING MORE SENT")
	}

	private fun persist(s: Session, ended: Boolean = false) {
		deliveryState.savePermit(s.id, SlackSessionPermit(s.generation, s.syncing(), s.paused, ended))
	}

	private fun outgoing(s: Session, kind: SlackMessageKind, text: String) =
		SlackOutgoing(s.id, kind, text, s.generation, s.settings.consentAtMillis)

	private fun maybeMask(s: Session, text: String) = if (s.settings.maskSensitive) SlackMessages.mask(text) else text

	private companion object {
		const val TAG = "SLACK_SYNC"
	}
}

/** Indirection so the coordinator can be tested without SharedPreferences. */
interface SlackDeliveryStateHolder {
	var currentSessionId: String?
	fun permit(id: String): SlackSessionPermit?
	fun savePermit(id: String, permit: SlackSessionPermit)
}

@kotlinx.serialization.Serializable
data class SlackSessionPermit(
	val generation: Int = 0,
	val enabled: Boolean = true,
	val paused: Boolean = false,
	val ended: Boolean = false,
)

object NoOpSlackEndScheduler : SlackEndScheduler {
	override fun schedule(request: SlackEndRequest) = Unit
}

