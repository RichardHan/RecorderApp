package com.eva.cloudtranscribe.slack

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.provider.VoiceRecordingsProvider
import com.eva.utils.Resource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Enqueues one unique end-message worker per Slack session (KEEP = idempotent). */
@Singleton
class WorkManagerSlackEndScheduler @Inject constructor(
	@ApplicationContext private val context: Context,
) : SlackEndScheduler {
	override fun schedule(request: SlackEndRequest) {
		val points = request.liveKeyPoints.take(40)
		val work = OneTimeWorkRequestBuilder<SlackEndWorker>()
			.setInputData(
				workDataOf(
					SlackEndWorker.KEY_SESSION to request.sessionId,
					SlackEndWorker.KEY_RECORDING to request.recordingId,
					SlackEndWorker.KEY_DURATION to request.durationMs,
					SlackEndWorker.KEY_TITLE to (request.title ?: ""),
					SlackEndWorker.KEY_POINTS to points.toTypedArray(),
					SlackEndWorker.KEY_MASK to request.maskSensitive,
					SlackEndWorker.KEY_ENQUEUED_AT to request.enqueuedAtMillis,
					SlackEndWorker.KEY_GENERATION to request.generation,
					SlackEndWorker.KEY_CONSENT to (request.consentAtMillis ?: -1L),
				),
			)
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
			.addTag(SlackEndWorker.TAG)
			.addTag(SlackEndWorker.tagFor(request.sessionId))
			.build()
		WorkManager.getInstance(context)
			.enqueueUniqueWork(SlackEndScheduler.uniqueName(request.sessionId), ExistingWorkPolicy.KEEP, work)
	}
}

/**
 * Waits for the existing post-recording cloud summary when that pipeline will run, then sends one concise
 * end message. Times out into a live-key-point fallback (optional one-shot condense if already configured).
 * App-process-safe: [Result.retry] continues the wait after the process is killed.
 */
@HiltWorker
class SlackEndWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val store: TranscriptStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
	private val recordings: VoiceRecordingsProvider,
	private val outbox: WorkManagerSlackOutbox,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val sessionId = inputData.getString(KEY_SESSION) ?: return Result.success()
		val recordingId = inputData.getLong(KEY_RECORDING, 0L)
		val durationMs = inputData.getLong(KEY_DURATION, 0L)
		val givenTitle = inputData.getString(KEY_TITLE)?.ifBlank { null }
		val livePoints = inputData.getStringArray(KEY_POINTS)?.toList().orEmpty()
		val mask = inputData.getBoolean(KEY_MASK, false)
		val enqueuedAt = inputData.getLong(KEY_ENQUEUED_AT, System.currentTimeMillis())
		val generation = inputData.getInt(KEY_GENERATION, 0)
		val consentAtMillis = inputData.getLong(KEY_CONSENT, -1).takeIf { it >= 0 }
		val now = System.currentTimeMillis()

		val records = if (recordingId > 0L) runCatching { store.summaries(recordingId) }.getOrDefault(emptyList()) else emptyList()
		val summary = SlackEndPolicy.pickPostRecordingSummary(records)
		val cloud = settingsRepo.current()
		val waitForPipeline = SlackEndPolicy.shouldWaitForPipeline(cloud.autoAfterRecording, cloud.autoSummaryAfterTranscript)
		when (
			SlackEndPolicy.decide(
				nowMs = now,
				enqueuedAtMs = enqueuedAt,
				waitForPipeline = waitForPipeline,
				summary = summary,
				inFlight = SlackEndPolicy.isInFlight(records),
			)
		) {
			SlackEndPolicy.Wait.WAIT -> return Result.retry()
			SlackEndPolicy.Wait.READY -> {
				val title = givenTitle ?: recordingTitle(recordingId)
				val text = maybeMask(
					mask,
					SlackEndMessage.compose(title, durationMs, summary?.text, livePoints),
				)
				outbox.enqueue(SlackOutgoing(sessionId, SlackMessageKind.END, text, generation, consentAtMillis))
				return Result.success()
			}
			SlackEndPolicy.Wait.FALLBACK -> {
				val title = givenTitle ?: recordingTitle(recordingId)
				val condensed = maybeCondense(cloud, livePoints)
				val text = maybeMask(
					mask,
					SlackEndMessage.compose(title, durationMs, summaryText = null, fallbackItems = livePoints, condensedItems = condensed),
				)
				outbox.enqueue(SlackOutgoing(sessionId, SlackMessageKind.END, text, generation, consentAtMillis))
				return Result.success()
			}
		}
	}

	private suspend fun recordingTitle(recordingId: Long): String? = withContext(Dispatchers.IO) {
		if (recordingId <= 0L) return@withContext null
		(recordings.getVoiceRecordingAsResourceFromId(recordingId) as? Resource.Success)
			?.data
			?.let { it.title.ifBlank { it.displayName } }
	}

	private suspend fun maybeCondense(settings: CloudSettings, items: List<String>): List<String>? {
		if (items.size <= SlackEndMessage.MAX_POINTS) return null
		val apiKey = keys.read() ?: return null
		if (settings.validate(apiKey.isNotBlank()) != null) return null
		return runCatching {
			val client = CloudAiClients.create(settings, apiKey, UrlConnectionTransport(), settings.summaryModel)
			val outcome = withContext(Dispatchers.IO) { client.complete(SlackEndCondense.prompt(items)) }
			(outcome as? CallOutcome.Text)?.text?.let { SlackEndCondense.parseReply(it) }?.takeIf { it.isNotEmpty() }
		}.getOrNull()
	}

	private fun maybeMask(mask: Boolean, text: String) = if (mask) SlackMessages.mask(text) else text

	companion object {
		const val TAG = "slack_end"
		const val KEY_SESSION = "session_id"
		const val KEY_RECORDING = "recording_id"
		const val KEY_DURATION = "duration_ms"
		const val KEY_TITLE = "title"
		const val KEY_POINTS = "live_points"
		const val KEY_MASK = "mask"
		const val KEY_ENQUEUED_AT = "enqueued_at"
		const val KEY_GENERATION = "generation"
		const val KEY_CONSENT = "consent_at"
		fun tagFor(sessionId: String) = "slack_end_$sessionId"
	}
}
