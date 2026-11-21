package com.eva.cloudtranscribe.work

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings

import com.eva.cloudtranscribe.util.boundedRetry

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
import com.eva.cloudtranscribe.citations.SummaryCitations
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.Redactor
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.audio.PcmDecoder
import com.eva.cloudtranscribe.audio.PcmFileSource
import com.eva.cloudtranscribe.engine.CloudTranscriptionEngine
import com.eva.cloudtranscribe.engine.EngineConfig
import com.eva.cloudtranscribe.engine.Prompts
import com.eva.cloudtranscribe.model.PiiRedactor
import com.eva.cloudtranscribe.model.AutoSummaryPolicy
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryFormat
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.title.AutoTitleRenamer
import com.eva.cloudtranscribe.title.AutoTitleResult
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
			model = s.transcribeModel, reasoningEffort = s.transcriptionEffort.wire,
			speakerDiarization = s.speakerDiarization,
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

	/**
	 * Creates a summary for [transcriptId], reusing an identical pending job; finished summaries are never overwritten. [format] lets the dialog ask
	 * for another shape than the saved default for this one run.
	 */
	suspend fun startSummary(
		recordingId: Long,
		transcriptId: String,
		format: SummaryFormat? = null,
		automatic: Boolean = false,
	): Result<String> {
		val s = settings.current()
		s.validate(keys.hasKey.value)?.let { return Result.failure(IllegalStateException(it)) }
		if (automatic) {
			val source = store.transcript(recordingId, transcriptId)
				?: return Result.failure(IllegalStateException(UiStrings.get(StringsRes.string.data_cloudtranscribe_source_transcript_missing, "來源逐字稿不存在")))
			if (!AutoSummaryPolicy.shouldStart(s, source.status, source.text, transcriptId, emptyList()))
				return Result.failure(IllegalStateException(UiStrings.get(StringsRes.string.data_cloudtranscribe_automatic_summary_conditions_not_met, "自動摘要條件不符")))
		}
		val id = "summary-${UUID.randomUUID()}"
		val claimed = store.claimSummary(
			newSummaryRecord(
				id = id,
				recordingId = recordingId,
				transcriptId = transcriptId,
				settings = s,
				format = (format ?: s.effectiveSummaryFormat).let { if (it == SummaryFormat.CUSTOM && s.customSummaryInstructions.isBlank()) SummaryFormat.CONCISE else it },
				now = System.currentTimeMillis(),
			),
			automatic = automatic,
		)
		if (claimed.id != id) return Result.success(claimed.id)
		val request = OneTimeWorkRequestBuilder<CloudSummaryWorker>()
			.setInputData(workDataOf(KEY_RECORDING to recordingId, KEY_RUN to id))
			.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
			.addTag(TAG).addTag(tagFor(recordingId)).build()
		try {
			withContext(Dispatchers.IO) { wm.enqueueUniqueWork(uniqueName(id), ExistingWorkPolicy.KEEP, request).result.get() }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			store.saveSummary(claimed.copy(status = RunStatus.FAILED, error = UiStrings.get(StringsRes.string.data_cloudtranscribe_could_not_schedule_summary, "無法排程摘要")))
			return Result.failure(e)
		}
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
	private val scheduler: CloudJobScheduler,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val recordingId = inputData.getLong(CloudJobScheduler.KEY_RECORDING, -1)
		val runId = inputData.getString(CloudJobScheduler.KEY_RUN) ?: return Result.failure()
		val record = store.transcript(recordingId, runId) ?: return Result.failure()
		val apiKey = keys.read() ?: return fail(record, UiStrings.get(StringsRes.string.data_cloudtranscribe_api_key_missing_alt, "找不到 API Key"))
		val current = settingsRepo.current()
		val p = record.provenance
		// Retry/resume always uses the run's own provider/model snapshot — never another provider.
		val s = current.copy(providerType = p.providerType ?: current.providerType, transcribeModel = p.model, speakerDiarization = p.speakerDiarization)
		runCatching { setForeground(foregroundInfo(UiStrings.get(StringsRes.string.data_cloudtranscribe_transcribing_in_the_cloud, "雲端轉錄中…"))) }
		val pcm = File(applicationContext.cacheDir, "cloud_ai_pcm/$runId.pcm")
		try {
			store.saveTranscript(record.copy(status = RunStatus.RUNNING, updatedAtMillis = System.currentTimeMillis()))
			val uri = recordingUri(recordingId)
			if (!pcm.exists() || pcm.length() == 0L) PcmDecoder.decodeToFile(applicationContext, uri, pcm)
			val client = CloudAiClients.transcriber(s, apiKey, UrlConnectionTransport())
			val engine = CloudTranscriptionEngine(client, store.checkpoints(recordingId, runId), EngineConfig(
				chunkSeconds = p.chunkSeconds ?: s.normalizedChunkSeconds,
				workers = p.workers ?: s.normalizedWorkers,
				hallucinationWarning = s.hallucinationWarning,
				skipSilentChunks = s.skipSilentChunks,
				prompt = Prompts.withVocabulary(Prompts.TRANSCRIBE, s.customVocabulary),
				alternatePrompt = Prompts.withVocabulary(Prompts.TRANSCRIBE_ALT, s.customVocabulary),
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
			maybeAutoSummary(recordingId, runId, result.status, result.text)
			notifyDone(recordingId, result.status)
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

	/** Opt-in automatic scheduling; the atomic store claim also checks manual jobs. Never fails transcription. */
	private suspend fun maybeAutoSummary(recordingId: Long, runId: String, status: RunStatus, text: String) {
		try {
			if (!AutoSummaryPolicy.shouldStart(settingsRepo.current(), status, text, runId, store.summaries(recordingId))) return
			scheduler.startSummary(recordingId, runId, automatic = true).onFailure { Log.w(CloudJobScheduler.TAG, "auto summary skipped: ${it.message}") }
		} catch (e: CancellationException) { throw e } catch (e: Exception) {
			Log.w(CloudJobScheduler.TAG, "auto summary not scheduled", e)
		}
	}

	private suspend fun fail(record: TranscriptRecord, message: String): Result {
		store.saveTranscript(record.copy(status = RunStatus.FAILED, error = message, updatedAtMillis = System.currentTimeMillis()))
		notifyDone(record.recordingId, RunStatus.FAILED)
		return Result.failure()
	}

	/** Opt-in only; never throws. Cancelled runs never reach here. */
	private suspend fun notifyDone(recordingId: Long, status: RunStatus) = runCatching {
		if (!settingsRepo.current().notifyWhenDone) return@runCatching
		val title = recordingTitle(recordings, recordingId)
		CloudDoneMessages.transcription(status, title)?.let { CloudDoneNotifier.post(applicationContext, recordingId, "transcript", it) }
	}

	private suspend fun recordingUri(id: Long): Uri = withContext(Dispatchers.IO) {
		when (val r = recordings.getVoiceRecordingAsResourceFromId(id)) {
			is Resource.Success -> r.data.fileUri.toUri()
			is Resource.Error -> throw r.error
			else -> throw IllegalStateException(UiStrings.get(StringsRes.string.data_cloudtranscribe_recording_not_found, "錄音不存在"))
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
	private val recordings: VoiceRecordingsProvider,
	private val autoTitle: AutoTitleRenamer,
) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val recordingId = inputData.getLong(CloudJobScheduler.KEY_RECORDING, -1)
		val id = inputData.getString(CloudJobScheduler.KEY_RUN) ?: return Result.failure()
		val record = store.summary(recordingId, id) ?: return Result.failure()
		val apiKey = keys.read() ?: return done(record.copy(status = RunStatus.FAILED, error = UiStrings.get(StringsRes.string.data_cloudtranscribe_api_key_missing_alt, "找不到 API Key")))
		val transcript = store.transcriptWithEdits(recordingId, record.sourceTranscriptId)
			?: return done(record.copy(status = RunStatus.FAILED, error = UiStrings.get(StringsRes.string.data_cloudtranscribe_source_transcript_missing, "來源逐字稿不存在")))
		val s = settingsRepo.current().let { it.copy(providerType = record.provenance.providerType ?: it.providerType) }
		val format = record.provenance.summaryFormat
			?.let { name -> SummaryFormat.entries.firstOrNull { it.name == name } }
			?: s.effectiveSummaryFormat
		store.saveSummary(record.copy(status = RunStatus.RUNNING))
		val client = CloudAiClients.create(s, apiKey, UrlConnectionTransport(), record.provenance.model)
		// optional on-device PII masking (#81) of everything taken from the recording
		val redact: (String) -> String = { if (s.redactPiiForCloud) PiiRedactor.redact(it) else it }
		// Citations only when the run asked for them AND the source has real cloud segment timing.
		val citeSegments = if (record.provenance.citations == true) SummaryCitations.segmentsOf(transcript) else null
		val basePrompt = if (citeSegments != null)
			SummaryCitations.withInstruction(Prompts.summary(redact(SummaryCitations.numberedTranscript(citeSegments)), format, s.customSummaryInstructions))
		else Prompts.summary(redact(transcript.text), format, s.customSummaryInstructions)
		// per-recording notes (#5) are reference-only; without notes the prompt is unchanged
		val prompt = com.eva.cloudtranscribe.notes.RecordingNotes.withNotes(
			basePrompt,
			runCatching { store.notes(recordingId)?.text?.let(redact) }.getOrNull(),
		)
		val last = boundedRetry(
			attempts = CloudSettings.MAX_ATTEMPTS,
			request = { withContext(Dispatchers.IO) { client.complete(prompt) } },
			shouldRetry = { it is CallOutcome.Failure && it.retryable },
			pause = { kotlinx.coroutines.delay(it) },
		)
		if (last is CallOutcome.Text) {
			if (citeSegments == null) return done(record.copy(status = RunStatus.SUCCESS, text = last.text))
			val cited = SummaryCitations.parse(last.text, citeSegments)
			return done(record.copy(status = RunStatus.SUCCESS, text = cited.text, citations = cited.citations))
		}
		val msg = when (val o = last) {
			is CallOutcome.Failure -> o.message
			CallOutcome.ContentFiltered -> UiStrings.get(StringsRes.string.data_cloudtranscribe_filtered, "內容過濾")
			CallOutcome.Empty -> UiStrings.get(StringsRes.string.data_cloudtranscribe_empty_model_response, "模型空白回應")
			else -> UiStrings.get(StringsRes.string.data_cloudtranscribe_failed, "失敗")
		}
		return done(record.copy(status = RunStatus.FAILED, error = msg))
	}

	private suspend fun done(r: SummaryRecord): Result {
		store.saveSummary(r)
		val renamed = if (r.status == RunStatus.SUCCESS)
			runCatching { autoTitle.applyIfEligible(r.recordingId) }.getOrNull() as? AutoTitleResult.Applied
		else null
		runCatching {
			if (settingsRepo.current().notifyWhenDone) CloudDoneMessages.summary(
				r.status,
				renamed?.displayName ?: recordingTitle(recordings, r.recordingId),
				r.text,
				autoTitled = renamed != null,
			)?.let { CloudDoneNotifier.post(applicationContext, r.recordingId, "summary", it) }
		}
		return if (r.status == RunStatus.SUCCESS) Result.success() else Result.failure()
	}
}

private suspend fun recordingTitle(recordings: VoiceRecordingsProvider, id: Long): String? = withContext(Dispatchers.IO) {
	(recordings.getVoiceRecordingAsResourceFromId(id) as? Resource.Success)?.data?.let { it.title.ifBlank { it.displayName } }
}

internal fun foregroundInfoFor(context: Context, id: Int, text: String) = buildForeground(context, id, text)

private fun buildForeground(context: Context, id: Int, text: String): ForegroundInfo {
	val channel = "cloud_ai_jobs"
	NotificationManagerCompat.from(context).createNotificationChannel(
		NotificationChannelCompat.Builder(channel, NotificationManagerCompat.IMPORTANCE_LOW).setName(UiStrings.get(StringsRes.string.data_cloudtranscribe_cloud_transcription, "雲端轉錄")).build(),
	)
	val notification = NotificationCompat.Builder(context, channel)
		.setSmallIcon(android.R.drawable.stat_sys_upload)
		.setContentTitle(UiStrings.get(StringsRes.string.data_cloudtranscribe_recorderapp, "RecorderApp"))
		.setContentText(text)
		.setOngoing(true)
		.build()
	return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
		ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
	else ForegroundInfo(id, notification)
}

/**
 * Single place that builds a queued summary record, so the format a run will use is snapshotted in its provenance and
 * a re-run can never be confused with the older summary it was derived from.
 */
internal fun newSummaryRecord(
	id: String,
	recordingId: Long,
	transcriptId: String,
	settings: CloudSettings,
	format: SummaryFormat,
	now: Long,
): SummaryRecord = SummaryRecord(
	id = id,
	recordingId = recordingId,
	sourceTranscriptId = transcriptId,
	provenance = Provenance(
		engine = "cloud-summary",
		providerType = settings.providerType,
		endpointHost = Redactor.hostOf(settings.baseUrl),
		model = settings.summaryModel,
		reasoningEffort = settings.reasoningEffort.wire,
		summaryFormat = format.name,
		citations = settings.summaryCitations.takeIf { it },
	),
	createdAtMillis = now,
	status = RunStatus.QUEUED,
)
