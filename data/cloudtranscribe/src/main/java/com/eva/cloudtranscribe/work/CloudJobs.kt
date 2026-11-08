package com.eva.cloudtranscribe.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.Redactor
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.audio.PcmDecoder
import com.eva.cloudtranscribe.audio.PcmFileSource
import com.eva.cloudtranscribe.engine.CloudTranscriptionEngine
import com.eva.cloudtranscribe.engine.EngineConfig
import com.eva.cloudtranscribe.engine.Prompts
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.SummaryStyle
import com.eva.cloudtranscribe.model.MeetingNotes
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.provider.VoiceRecordingsProvider
import com.eva.utils.Resource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Public entry point used by UI and the recorder service. */
@Singleton
class CloudJobScheduler @Inject constructor(
	@ApplicationContext private val context: Context,
	private val store: TranscriptStore,
	private val settings: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) {
	private val wm get() = WorkManager.getInstance(context)

	/** Explicit user action. Creates a new run (snapshotting current settings) or resumes [resumeRunId]. */
	suspend fun startTranscription(recordingId: Long, resumeRunId: String? = null, trigger: String = "manual"): Result<String> {
		val s = settings.current()
		s.validate(keys.hasKey.value)?.let { return Result.failure(IllegalStateException(it)) }
		val runId = resumeRunId ?: "cloud-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}"
		val existing = resumeRunId?.let { store.transcript(recordingId, it) }
		val snapshot = existing?.provenance ?: Provenance(
			engine = "cloud", providerType = s.providerType, endpointHost = Redactor.hostOf(s.baseUrl),
			model = s.transcribeModel, reasoningEffort = s.reasoningEffort.wire,
			chunkSeconds = s.normalizedChunkSeconds, workers = s.normalizedWorkers,
			audioFormat = "mp3 16kHz mono 64kbps", trigger = trigger,
		)
		val now = System.currentTimeMillis()
		store.saveTranscript(
			(existing ?: TranscriptRecord(runId, recordingId, TranscriptSource.CLOUD, snapshot, now, status = RunStatus.QUEUED))
				.copy(status = RunStatus.QUEUED, updatedAtMillis = now, error = null),
		)
		val request = OneTimeWorkRequestBuilder<CloudTranscribeWorker>()
			.setInputData(workDataOf(KEY_RECORDING to recordingId, KEY_RUN to runId))
			.setConstraints(Constraints.Builder().setRequiredNetworkType(
				if (trigger == "auto" && s.autoWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
			.addTag(TAG).addTag(tagFor(recordingId))
			.build()
		wm.enqueueUniqueWork(uniqueName(runId), ExistingWorkPolicy.KEEP, request)
		return Result.success(runId)
	}

	suspend fun startSummary(recordingId: Long, transcriptId: String, style: SummaryStyle = SummaryStyle.BRIEF, expectedSettings: CloudSettings? = null): Result<String> {
		val transcript = store.transcript(recordingId, transcriptId)
			?: return Result.failure(IllegalArgumentException("來源逐字稿不存在"))
		if (if (style == SummaryStyle.MEETING_NOTES) MeetingNotes.sources(transcript).isEmpty() else transcript.text.isBlank()) return Result.failure(IllegalArgumentException("來源逐字稿沒有可用內容"))
		val s = settings.current()
		s.validate(keys.hasKey.value)?.let { return Result.failure(IllegalStateException(it)) }
		if (expectedSettings != null && (s.baseUrl != expectedSettings.baseUrl || s.providerType != expectedSettings.providerType || s.summaryModel != expectedSettings.summaryModel)) return Result.failure(IllegalStateException("摘要服務設定已變更，請重新確認送出"))
		val id = "summary-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}"
		store.saveSummary(SummaryRecord(id, recordingId, transcriptId,
			Provenance("cloud-summary", s.providerType, Redactor.hostOf(s.baseUrl), s.summaryModel, s.reasoningEffort.wire),
			System.currentTimeMillis(), RunStatus.QUEUED, style = style, approvedEndpoint = s.baseUrl))
		val request = OneTimeWorkRequestBuilder<CloudSummaryWorker>()
			.setInputData(workDataOf(KEY_RECORDING to recordingId, KEY_RUN to id))
			.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
			.addTag(TAG).addTag(tagFor(recordingId)).build()
		wm.enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.KEEP, request)
		return Result.success(id)
	}

	fun cancel(runId: String) { wm.cancelUniqueWork(uniqueName(runId)) }

	fun observe(recordingId: Long): Flow<List<WorkInfo>> = wm.getWorkInfosByTagFlow(tagFor(recordingId))

	/** Called by the recorder only after a recording was saved successfully (never on cancel). */
	suspend fun onRecordingFinalized(recordingId: Long) {
		try {
			val s = settings.current()
			if (!s.autoAfterRecording) return
			startTranscription(recordingId, trigger = "auto").onFailure { Log.w(TAG, "auto cloud transcription skipped: ${it.message}") }
		} catch (e: CancellationException) { throw e } catch (e: Exception) {
			Log.w(TAG, "auto cloud transcription not scheduled", e)
		}
	}

	companion object {
		const val TAG = "cloud_ai"
		const val KEY_RECORDING = "recording_id"
		const val KEY_RUN = "run_id"
		const val PROGRESS_DONE = "done"
		const val PROGRESS_TOTAL = "total"
		fun tagFor(recordingId: Long) = "cloud_ai_rec_$recordingId"
		fun uniqueName(runId: String) = "cloud_ai_$runId"
	}
}

@HiltWorker
class CloudTranscribeWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val store: TranscriptStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
	private val recordings: VoiceRecordingsProvider,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val recordingId = inputData.getLong(CloudJobScheduler.KEY_RECORDING, -1)
		val runId = inputData.getString(CloudJobScheduler.KEY_RUN) ?: return Result.failure()
		val record = store.transcript(recordingId, runId) ?: return Result.failure()
		val apiKey = keys.read() ?: return fail(record, "找不到 API Key")
		val current = settingsRepo.current()
		val p = record.provenance
		// Retry/resume always uses the run's own provider/model snapshot — never another provider.
		val s = current.copy(providerType = p.providerType ?: current.providerType, transcribeModel = p.model)
		runCatching { setForeground(foregroundInfo("雲端轉錄中…")) }
		val pcm = File(applicationContext.cacheDir, "cloud_ai_pcm/$runId.pcm")
		try {
			store.saveTranscript(record.copy(status = RunStatus.RUNNING, updatedAtMillis = System.currentTimeMillis()))
			val uri = recordingUri(recordingId)
			if (!pcm.exists() || pcm.length() == 0L) PcmDecoder.decodeToFile(applicationContext, uri, pcm)
			val client = CloudAiClients.create(s, apiKey, UrlConnectionTransport(), p.model)
			val engine = CloudTranscriptionEngine(client, store.checkpoints(recordingId, runId), EngineConfig(
				chunkSeconds = p.chunkSeconds ?: s.normalizedChunkSeconds,
				workers = p.workers ?: s.normalizedWorkers,
				hallucinationWarning = s.hallucinationWarning,
				skipSilentChunks = s.skipSilentChunks,
			))
			val result = engine.run(PcmFileSource(pcm)) { done, total ->
				setProgress(workDataOf(CloudJobScheduler.PROGRESS_DONE to done, CloudJobScheduler.PROGRESS_TOTAL to total))
				store.transcript(recordingId, runId)?.let { store.saveTranscript(it.copy(completedSegments = done, totalSegments = total, updatedAtMillis = System.currentTimeMillis())) }
			}
			store.saveTranscript(record.copy(
				status = result.status, text = result.text, segments = result.segments, warnings = result.warnings,
				totalSegments = result.segments.size, completedSegments = result.segments.count { it.isFinal },
				requestCount = record.requestCount + result.requestCount, updatedAtMillis = System.currentTimeMillis(), error = null,
			))
			if (result.status == RunStatus.SUCCESS) store.clearCheckpoints(recordingId, runId)
			pcm.delete()
			return Result.success()
		} catch (e: CancellationException) {
			store.transcript(recordingId, runId)?.let { store.saveTranscript(it.copy(status = RunStatus.CANCELLED, updatedAtMillis = System.currentTimeMillis())) }
			pcm.delete()
			throw e
		} catch (e: Exception) {
			pcm.delete()
			return fail(record, Redactor.redact(e.message ?: e.javaClass.simpleName, apiKey))
		}
	}

	private fun fail(record: TranscriptRecord, message: String): Result {
		store.saveTranscript(record.copy(status = RunStatus.FAILED, error = message, updatedAtMillis = System.currentTimeMillis()))
		return Result.failure()
	}

	private suspend fun recordingUri(id: Long): Uri = withContext(Dispatchers.IO) {
		when (val r = recordings.getVoiceRecordingAsResourceFromId(id)) {
			is Resource.Success -> r.data.fileUri.toUri()
			is Resource.Error -> throw r.error
			else -> throw IllegalStateException("錄音不存在")
		}
	}

	private fun foregroundInfo(text: String): ForegroundInfo = buildForeground(applicationContext, id.hashCode(), text)
}

@HiltWorker
class CloudSummaryWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val store: TranscriptStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val recordingId = inputData.getLong(CloudJobScheduler.KEY_RECORDING, -1)
		val id = inputData.getString(CloudJobScheduler.KEY_RUN) ?: return Result.failure()
		val record = store.summary(recordingId, id) ?: return Result.failure()
		val apiKey = keys.read() ?: return done(record.copy(status = RunStatus.FAILED, error = "找不到 API Key"))
		val transcript = store.transcript(recordingId, record.sourceTranscriptId)
			?: return done(record.copy(status = RunStatus.FAILED, error = "來源逐字稿不存在"))
		if (if (record.style == SummaryStyle.MEETING_NOTES) MeetingNotes.sources(transcript).isEmpty() else transcript.text.isBlank()) return done(record.copy(status = RunStatus.FAILED, error = "來源逐字稿沒有可用內容"))
		val prompt = if (record.style == SummaryStyle.MEETING_NOTES) MeetingNotes.prompt(transcript) else Prompts.summary(transcript.text)
		val s = settingsRepo.current()
		if ((record.approvedEndpoint != null && s.baseUrl != record.approvedEndpoint) || s.providerType != record.provenance.providerType || Redactor.hostOf(s.baseUrl) != record.provenance.endpointHost) return done(record.copy(status = RunStatus.FAILED, error = "摘要服務已變更，請重新確認送出"))
		store.saveSummary(record.copy(status = RunStatus.RUNNING))
		val client = CloudAiClients.create(s, apiKey, UrlConnectionTransport(), record.provenance.model)
		var last: CallOutcome? = null
		repeat(CloudSettings.MAX_ATTEMPTS) { attempt ->
			last = withContext(Dispatchers.IO) { client.complete(prompt) }
			val outcome = last
			if (outcome is CallOutcome.Text) {
				if (record.style == SummaryStyle.BRIEF) return done(record.copy(status = RunStatus.SUCCESS, text = outcome.text))
				val rendered = MeetingNotes.render(outcome.text, transcript)
				return done(record.copy(status = if (rendered.complete) RunStatus.SUCCESS else RunStatus.PARTIAL, text = rendered.text, sourceSnapshot = MeetingNotes.sources(transcript), sourceTranscriptUpdatedAtMillis = transcript.updatedAtMillis))
			}
			if (outcome !is CallOutcome.Failure || !outcome.retryable) return@repeat
			kotlinx.coroutines.delay(2000L * (attempt + 1))
		}
		val msg = when (val o = last) {
			is CallOutcome.Failure -> o.message
			CallOutcome.ContentFiltered -> "內容過濾"
			CallOutcome.Empty -> "模型空白回應"
			else -> "失敗"
		}
		return done(record.copy(status = RunStatus.FAILED, error = msg))
	}

	private fun done(r: SummaryRecord): Result { store.saveSummary(r); return if (r.status == RunStatus.SUCCESS || r.status == RunStatus.PARTIAL) Result.success() else Result.failure() }
}

internal fun foregroundInfoFor(context: Context, id: Int, text: String) = buildForeground(context, id, text)

private fun buildForeground(context: Context, id: Int, text: String): ForegroundInfo {
	val channel = "cloud_ai_jobs"
	NotificationManagerCompat.from(context).createNotificationChannel(
		NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_LOW).setName("雲端轉錄").build(),
	)
	val notification = NotificationCompat.Builder(context, channel)
		.setSmallIcon(android.R.drawable.stat_sys_upload)
		.setContentTitle("RecorderApp")
		.setContentText(text)
		.setOngoing(true)
		.build()
	return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
		ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
	else ForegroundInfo(id, notification)
}
