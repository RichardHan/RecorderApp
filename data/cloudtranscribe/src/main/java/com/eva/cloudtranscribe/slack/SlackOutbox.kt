package com.eva.cloudtranscribe.slack

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One message waiting to be delivered. */
data class SlackOutgoing(
	val sessionId: String,
	val kind: SlackMessageKind,
	val text: String,
	val generation: Int = 0,
	val consentAtMillis: Long? = null,
) {
	val hash: String get() = SlackMessages.hash(kind, "$sessionId:$generation", text)
}

/** Where the coordinator hands finished messages. */
interface SlackOutbox {
	fun enqueue(message: SlackOutgoing)
}

/**
 * Small persistent bookkeeping (SharedPreferences, not backed up): the current recording session, which messages
 * were already delivered (de-duplication), and the last delivery error for the UI.
 */
@Singleton
class SlackDeliveryState @Inject constructor(@ApplicationContext context: Context) {
	private val prefs = context.getSharedPreferences("slack_sync_state", Context.MODE_PRIVATE)
	private val _lastError = MutableStateFlow(prefs.getString(KEY_ERROR, null))
	val lastError: StateFlow<String?> = _lastError.asStateFlow()

	var currentSessionId: String?
		get() = prefs.getString(KEY_SESSION, null)
		set(value) {
			prefs.edit().putString(KEY_SESSION, value).apply()
		}

	fun permit(id: String): SlackSessionPermit? = prefs.getString("permit.$id", null)?.let {
		runCatching { kotlinx.serialization.json.Json.decodeFromString(SlackSessionPermit.serializer(), it) }.getOrNull()
	}

	fun savePermit(id: String, permit: SlackSessionPermit) {
		check(prefs.edit().putString("permit.$id", kotlinx.serialization.json.Json.encodeToString(SlackSessionPermit.serializer(), permit)).commit())
	}

	@Synchronized
	fun wasSent(hash: String): Boolean = hash in prefs.getStringSet(KEY_SENT, emptySet()).orEmpty()

	@Synchronized
	fun markSent(hash: String) {
		val list = prefs.getString(KEY_SENT_ORDER, "").orEmpty().split(',').filter { it.isNotEmpty() }
		val next = (list + hash).takeLast(MAX_REMEMBERED)
		prefs.edit().putStringSet(KEY_SENT, next.toSet()).putString(KEY_SENT_ORDER, next.joinToString(",")).apply()
	}

	/** Persist the server deadline so a restarted worker cannot retry early. */
	internal val retryGate = SlackRetryGate(
		readDeadline = { prefs.getLong("retry_not_before", 0L) },
		writeDeadline = { prefs.edit().putLong("retry_not_before", it).commit(); Unit },
	)

	fun setError(message: String?) {
		prefs.edit().putString(KEY_ERROR, message).apply()
		_lastError.value = message
	}

	private companion object {
		const val KEY_SESSION = "current_session"
		const val KEY_SENT = "sent_hashes"
		const val KEY_SENT_ORDER = "sent_order"
		const val KEY_ERROR = "last_error"
		const val MAX_REMEMBERED = 200
	}
}

/** WorkManager-backed outbox: survives process death, waits for network, retries with back-off. */
@Singleton
class WorkManagerSlackOutbox @Inject constructor(
	@ApplicationContext private val context: Context,
	private val payloads: SlackPayloadStore,
) : SlackOutbox {
	override fun enqueue(message: SlackOutgoing) {
		val payload = payloads.save(message.text)
		try {
			val request = OneTimeWorkRequestBuilder<SlackSendWorker>()
				.setInputData(slackWorkData(message, payload))
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
				.addTag(SlackSendWorker.TAG)
				.build()
			// one queue: messages are delivered in order, one at a time
			WorkManager.getInstance(context)
				.enqueueUniqueWork(SlackSendWorker.QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
		} catch (e: Exception) {
			payloads.delete(payload)
			throw e
		}
	}
}

internal fun slackWorkData(message: SlackOutgoing, payload: String) = workDataOf(
	SlackSendWorker.KEY_SESSION to message.sessionId,
	SlackSendWorker.KEY_KIND to message.kind.name,
	SlackSendWorker.KEY_PAYLOAD to payload,
	SlackSendWorker.KEY_GENERATION to message.generation,
	SlackSendWorker.KEY_CONSENT to (message.consentAtMillis ?: -1L),
)

/** Decides whether a queued message may still go out at delivery time. Pure for tests. */
object SlackDeliveryRules {
	fun shouldDeliver(
		message: SlackOutgoing,
		settings: SlackSyncSettings,
		hasWebhook: Boolean,
		currentSessionId: String?,
		alreadySent: Boolean,
		permit: SlackSessionPermit? = SlackSessionPermit(),
	): Boolean {
		if (alreadySent || !hasWebhook) return false
		if (message.kind == SlackMessageKind.TEST) return true
		if (!settings.isActive || message.consentAtMillis != settings.consentAtMillis) return false
		if (permit == null || !permit.enabled || permit.generation != message.generation) return false
		if (message.kind != SlackMessageKind.END && (permit.ended || message.sessionId != currentSessionId)) return false
		// session isolation: an old recording's key points never go out once a new recording started
		if (message.kind == SlackMessageKind.KEY_POINTS && message.sessionId != currentSessionId) return false
		return true
	}
}

@HiltWorker
class SlackSendWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val settingsRepo: SlackSyncSettingsRepository,
	private val webhookStore: SlackWebhookStore,
	private val state: SlackDeliveryState,
	private val payloads: SlackPayloadStore,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val payload = inputData.getString(KEY_PAYLOAD)
		fun finished(): Result {
			payload?.let(payloads::delete)
			return Result.success()
		}
		val text = if (payload == null) inputData.getString(KEY_TEXT) else runCatching { payloads.read(payload) }.getOrNull()
		if (text == null) {
			state.setError("待送訊息內容已遺失，未送出")
			return finished()
		}
		val kind = inputData.getString(KEY_KIND)?.let { name -> SlackMessageKind.entries.firstOrNull { it.name == name } }
			?: return finished()
		val message = SlackOutgoing(
			sessionId = inputData.getString(KEY_SESSION).orEmpty(),
			kind = kind,
			text = text,
			generation = inputData.getInt(KEY_GENERATION, 0),
			consentAtMillis = inputData.getLong(KEY_CONSENT, -1).takeIf { it >= 0 },
		)
		val url = webhookStore.read()
		suspend fun allowed(): Boolean = SlackDeliveryRules.shouldDeliver(
			message, settingsRepo.current(), !url.isNullOrBlank(), state.currentSessionId,
			state.wasSent(message.hash), state.permit(message.sessionId),
		) && webhookStore.read() == url
		fun paused() = message.kind != SlackMessageKind.TEST && state.permit(message.sessionId)?.paused == true && message.kind != SlackMessageKind.END
		if (!allowed() || url == null) return finished()
		if (paused() || !state.retryGate.canSend()) return Result.retry()
		return when (val result = sender.send(url, message.text) { allowed() && !paused() }) {
			SlackSendResult.Suppressed -> if (allowed() && paused()) Result.retry() else finished()
			SlackSendResult.Sent -> {
				state.markSent(message.hash)
				state.setError(null)
				finished()
			}

			is SlackSendResult.Permanent -> {
				state.setError(SlackSendPolicy.describe(result))
				finished()
			}

			is SlackSendResult.Retry, is SlackSendResult.RetryAfter -> {
				state.setError(SlackSendPolicy.describe(result))
				if (result is SlackSendResult.RetryAfter) state.retryGate.defer(result.seconds)
				if (runAttemptCount >= MAX_ATTEMPTS) finished() else Result.retry()
			}
		}
	}

	companion object {
		const val KEY_SESSION = "session"
		const val KEY_KIND = "kind"
		const val KEY_TEXT = "text" // legacy work queued before file-backed payloads
		const val KEY_PAYLOAD = "payload_file"
		const val KEY_GENERATION = "generation"
		const val KEY_CONSENT = "consent_at"
		const val TAG = "slack_sync"
		const val QUEUE = "slack_sync_queue"
		const val MAX_ATTEMPTS = 8

		/** Shared so spacing between posts holds across workers in one process. */
		internal val sender by lazy { SlackSender(UrlConnectionSlackTransport()) }
	}
}
