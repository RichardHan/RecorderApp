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
) {
	val hash: String get() = SlackMessages.hash(kind, sessionId, text)
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

	@Synchronized
	fun wasSent(hash: String): Boolean = hash in prefs.getStringSet(KEY_SENT, emptySet()).orEmpty()

	@Synchronized
	fun markSent(hash: String) {
		val list = prefs.getString(KEY_SENT_ORDER, "").orEmpty().split(',').filter { it.isNotEmpty() }
		val next = (list + hash).takeLast(MAX_REMEMBERED)
		prefs.edit().putStringSet(KEY_SENT, next.toSet()).putString(KEY_SENT_ORDER, next.joinToString(",")).apply()
	}

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
class WorkManagerSlackOutbox @Inject constructor(@ApplicationContext private val context: Context) : SlackOutbox {
	override fun enqueue(message: SlackOutgoing) {
		val request = OneTimeWorkRequestBuilder<SlackSendWorker>()
			.setInputData(
				workDataOf(
					SlackSendWorker.KEY_SESSION to message.sessionId,
					SlackSendWorker.KEY_KIND to message.kind.name,
					SlackSendWorker.KEY_TEXT to message.text,
				),
			)
			.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
			.addTag(SlackSendWorker.TAG)
			.build()
		// one queue: messages are delivered in order, one at a time
		WorkManager.getInstance(context)
			.enqueueUniqueWork(SlackSendWorker.QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
	}
}

/** Decides whether a queued message may still go out at delivery time. Pure for tests. */
object SlackDeliveryRules {
	fun shouldDeliver(
		message: SlackOutgoing,
		settings: SlackSyncSettings,
		hasWebhook: Boolean,
		currentSessionId: String?,
		alreadySent: Boolean,
	): Boolean {
		if (alreadySent || !hasWebhook) return false
		if (message.kind == SlackMessageKind.TEST) return true
		if (!settings.isActive) return false
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
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val kind = inputData.getString(KEY_KIND)?.let { name -> SlackMessageKind.entries.firstOrNull { it.name == name } }
			?: return Result.failure()
		val message = SlackOutgoing(
			sessionId = inputData.getString(KEY_SESSION).orEmpty(),
			kind = kind,
			text = inputData.getString(KEY_TEXT).orEmpty(),
		)
		val url = webhookStore.read()
		val allowed = SlackDeliveryRules.shouldDeliver(
			message = message,
			settings = settingsRepo.current(),
			hasWebhook = !url.isNullOrBlank(),
			currentSessionId = state.currentSessionId,
			alreadySent = state.wasSent(message.hash),
		)
		if (!allowed || url == null) return Result.success()
		return when (val result = sender.send(url, message.text)) {
			SlackSendResult.Sent -> {
				state.markSent(message.hash)
				state.setError(null)
				Result.success()
			}

			is SlackSendResult.Permanent -> {
				state.setError(SlackSendPolicy.describe(result))
				Result.success()
			}

			is SlackSendResult.Retry, is SlackSendResult.RetryAfter -> {
				state.setError(SlackSendPolicy.describe(result))
				if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
			}
		}
	}

	companion object {
		const val KEY_SESSION = "session"
		const val KEY_KIND = "kind"
		const val KEY_TEXT = "text"
		const val TAG = "slack_sync"
		const val QUEUE = "slack_sync_queue"
		const val MAX_ATTEMPTS = 8

		/** Shared so spacing between posts holds across workers in one process. */
		internal val sender by lazy { SlackSender(UrlConnectionSlackTransport()) }
	}
}
