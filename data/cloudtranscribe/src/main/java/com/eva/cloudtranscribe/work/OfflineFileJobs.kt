package com.eva.cloudtranscribe.work

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eva.cloudtranscribe.audio.PcmDecoder
import com.eva.cloudtranscribe.audio.PcmFileSource
import com.eva.cloudtranscribe.audio.TARGET_RATE
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.provider.VoiceRecordingsProvider
import com.eva.transcribe.domain.models.STTModel
import com.eva.transcribe.domain.models.STTModelState
import com.eva.transcribe.domain.repository.STTModelsRepository
import com.eva.transcribe.offline.OfflineEngines
import com.eva.cloudtranscribe.model.OfflineTranscriptSegments
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.transcribe.offline.OfflineSpeechEngine
import com.eva.transcribe.offline.UtteranceSegmenter
import com.eva.utils.Resource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/** On-device re-transcription of a saved recording with a downloaded sherpa-onnx model. Nothing is uploaded. */
@Singleton
class OfflineFileTranscriber @Inject constructor(
	@ApplicationContext private val context: Context,
	private val store: TranscriptStore,
	private val models: STTModelsRepository,
) {
	/** Downloaded models usable for file re-transcription. */
	val availableModels: Flow<List<STTModel>> = models.readAllSSTModels().mapNotNull { r ->
		(r as? Resource.Success)?.data?.filter { OfflineEngines.isSherpa(it.engine) && it.state == STTModelState.DOWNLOADED }
	}

	fun start(recordingId: Long, model: STTModel): String {
		val id = "offline-${System.currentTimeMillis()}"
		store.saveTranscript(TranscriptRecord(id, recordingId, TranscriptSource.OFFLINE,
			Provenance(engine = model.engine, model = model.modelId, audioFormat = "16kHz mono，分句辨識（VAD）", trigger = "file"),
			System.currentTimeMillis(), status = RunStatus.QUEUED))
		val req = OneTimeWorkRequestBuilder<OfflineFileTranscribeWorker>()
			.setInputData(workDataOf(CloudJobScheduler.KEY_RECORDING to recordingId, CloudJobScheduler.KEY_RUN to id, KEY_MODEL to model.modelId))
			.addTag(CloudJobScheduler.TAG).addTag(CloudJobScheduler.tagFor(recordingId)).build()
		WorkManager.getInstance(context).enqueueUniqueWork(CloudJobScheduler.uniqueName(id), ExistingWorkPolicy.KEEP, req)
		return id
	}

	companion object { const val KEY_MODEL = "model_id" }
}

@HiltWorker
class OfflineFileTranscribeWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted params: WorkerParameters,
	private val store: TranscriptStore,
	private val engine: OfflineSpeechEngine,
	private val recordings: VoiceRecordingsProvider,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val recordingId = inputData.getLong(CloudJobScheduler.KEY_RECORDING, -1)
		val id = inputData.getString(CloudJobScheduler.KEY_RUN) ?: return Result.failure()
		val modelId = inputData.getString(OfflineFileTranscriber.KEY_MODEL) ?: return Result.failure()
		var record = store.transcript(recordingId, id) ?: return Result.failure()
		runCatching { setForeground(foregroundInfoFor(applicationContext, id.hashCode(), "離線轉錄中…")) }
		val pcm = File(applicationContext.cacheDir, "offline_pcm/$id.pcm")
		var loaded = false
		try {
			record = record.copy(status = RunStatus.RUNNING); store.saveTranscript(record)
			val uri = when (val r = recordings.getVoiceRecordingAsResourceFromId(recordingId)) {
				is Resource.Success -> r.data.fileUri.toUri()
				is Resource.Error -> throw r.error
				else -> error("錄音不存在")
			}
			PcmDecoder.decodeToFile(applicationContext, uri, pcm)
			engine.load(modelId).getOrThrow(); loaded = true
			val source = PcmFileSource(pcm)
			val segmenter = UtteranceSegmenter()
			val texts = ArrayList<String>()
			val segments = ArrayList<SegmentResult>()
			var decoded = 0; var failed = 0
			val totalMs = source.durationMs
			val blockMs = 1000L
			var pos = 0L
			suspend fun handle(u: UtteranceSegmenter.Utterance) {
				var error: String? = null
				val text = try { engine.decode(u.samples) } catch (e: CancellationException) { throw e } catch (e: Exception) {
					failed++; error = e.message ?: e.javaClass.simpleName; "[⚠ ${fmt(u.startSample * 1000 / TARGET_RATE)} 此段辨識失敗：${e.message}]"
				}
				if (text.isNotBlank()) texts += text
				segments += OfflineTranscriptSegments.fromSamples(decoded, u.startSample, u.samples.size, text, error)
				decoded++
				record = record.copy(text = texts.joinToString("\n"), completedSegments = decoded - failed, segments = segments.toList(), updatedAtMillis = System.currentTimeMillis())
				store.saveTranscript(record)
				setProgress(workDataOf(CloudJobScheduler.PROGRESS_DONE to (pos / 1000).toInt(), CloudJobScheduler.PROGRESS_TOTAL to (totalMs / 1000).toInt()))
			}
			while (pos < totalMs) {
				val block = source.read(pos, minOf(totalMs, pos + blockMs))
				for (u in segmenter.accept(block)) handle(u)
				pos += blockMs
			}
			for (u in segmenter.flush()) handle(u)
			store.saveTranscript(record.copy(
				status = if (failed == 0) RunStatus.SUCCESS else RunStatus.PARTIAL,
				totalSegments = decoded, completedSegments = decoded - failed,
				warnings = if (failed > 0) listOf("$failed 段辨識失敗") else emptyList(),
				updatedAtMillis = System.currentTimeMillis(),
			))
			return Result.success()
		} catch (e: CancellationException) {
			store.transcript(recordingId, id)?.let { store.saveTranscript(it.copy(status = RunStatus.CANCELLED)) }
			throw e
		} catch (e: Exception) {
			store.saveTranscript(record.copy(status = RunStatus.FAILED, error = e.message ?: e.javaClass.simpleName))
			return Result.failure()
		} finally {
			pcm.delete()
			if (loaded) engine.release()
		}
	}

	private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) }
}
