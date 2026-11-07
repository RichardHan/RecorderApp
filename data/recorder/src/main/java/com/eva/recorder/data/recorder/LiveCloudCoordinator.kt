package com.eva.recorder.data.recorder

import android.util.Log
import com.eva.cloudtranscribe.api.AudioPayload
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.OpenAiCompatibleClient
import com.eva.cloudtranscribe.api.Redactor
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.audio.Mp3Encoder
import com.eva.cloudtranscribe.engine.Prompts
import com.eva.cloudtranscribe.live.LiveCloudPhase
import com.eva.cloudtranscribe.live.LiveCloudSession
import com.eva.cloudtranscribe.live.LiveCloudSettingsRepository
import com.eva.cloudtranscribe.live.LiveCloudState
import com.eva.cloudtranscribe.live.OfflineLine
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.ProviderType
import com.eva.cloudtranscribe.model.ReasoningEffort
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SegmentStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.secure.SummaryApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recorder.domain.recorder.AudioByteDataProvider
import com.eva.recorder.domain.recorder.TranscriptionProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LIVE_CLOUD"

/**
 * Glue for the advanced live-cloud mode (default OFF). With default settings [start] returns immediately and no
 * network request is ever made. Audio is taken from the live PCM capture stream (16 kHz mono), never from the
 * recorder's still-growing output file.
 */
internal class LiveCloudCoordinator(
	private val source: AudioByteDataProvider,
	private val transcriber: TranscriptionProvider,
	private val liveRepo: LiveCloudSettingsRepository,
	private val cloudRepo: CloudSettingsRepository,
	private val asrKeys: ApiKeyStore,
	private val summaryKeys: SummaryApiKeyStore,
	private val store: TranscriptStore,
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val _state = MutableStateFlow(LiveCloudState())
	val state: StateFlow<LiveCloudState> = _state.asStateFlow()

	private var session: LiveCloudSession? = null
	private val jobs = mutableListOf<Job>()
	private var transcribeModel: String? = null
	private var endpointHost: String? = null

	suspend fun start() {
		val live = liveRepo.current()
		if (!live.isActive) return // default: OFF, nothing happens
		val cloud = cloudRepo.current()
		val asrKey = asrKeys.read()
		cloud.validate(asrKey != null)?.let { problem ->
			_state.value = LiveCloudState(active = true, phase = LiveCloudPhase.DONE, transcribeError = "雲端轉錄未啟動：$problem")
			return
		}
		val summaryKey = if (live.summaryReuseTranscribeKey) asrKey else summaryKeys.read()
		val summaryProblem = live.validateSummary(summaryKeys.read() != null, asrKey != null)
		val transport = UrlConnectionTransport()
		val asrClient = CloudAiClients.create(cloud, asrKey!!, transport, cloud.transcribeModel)
		val summaryClient = if (summaryProblem == null && summaryKey != null)
			OpenAiCompatibleClient(live.summaryBaseUrl, summaryKey, live.summaryModel, ReasoningEffort.OFF, transport)
		else null
		transcribeModel = cloud.transcribeModel
		endpointHost = Redactor.hostOf(cloud.baseUrl)

		val s = LiveCloudSession(
			scope = scope,
			settings = live,
			sessionId = UUID.randomUUID().toString(),
			transcribe = { samples, _, _ ->
				val mp3 = withContext(Dispatchers.Default) { Mp3Encoder.encode(samples, 16_000) }
				asrClient.transcribe(Prompts.TRANSCRIBE, AudioPayload(mp3, "mp3"))
			},
			summarize = { prompt ->
				summaryClient?.complete(prompt) ?: CallOutcome.Failure("重點整理未啟動：${summaryProblem ?: "缺少摘要 Key"}", retryable = false)
			},
			transcribeModel = cloud.transcribeModel,
		)
		session = s
		jobs += scope.launch { s.state.collect { _state.value = it } }
		jobs += scope.launch { source.stream.collect { (buffer, size) -> if (size > 0) s.onAudio(buffer, size) } }
		jobs += scope.launch { transcriber.offlineTimedLines.collect { l -> s.onOfflineLines(l.map { (ms, t) -> OfflineLine(ms, t) }) } }
		Log.i(TAG, "live cloud session started")
	}

	/** Flush + wait (bounded) and persist results as NEW immutable records; never touches audio or other transcripts. */
	suspend fun finishAndSave(recordingId: Long) {
		val s = session ?: return
		jobs.forEach { it.cancel() }
		val end = try { s.finish() } catch (e: Exception) { Log.e(TAG, "finish failed", e); return }
		session = null
		val now = System.currentTimeMillis()
		val spans = end.cloudSpans
		if (spans.isEmpty()) return
		val transcriptId = "cloud-live-$now"
		val segments = spans.mapIndexed { i, sp ->
			SegmentResult(
				index = i, startMs = sp.startMs, endMs = sp.endMs,
				status = when { !sp.ok || sp.suspicious -> SegmentStatus.FAILED; sp.text.isBlank() -> SegmentStatus.SILENT; else -> SegmentStatus.OK },
				text = sp.text, attempts = 1, error = sp.error,
			)
		}
		val allOk = spans.all { it.ok && !it.suspicious }
		runCatching {
			store.saveTranscript(
				TranscriptRecord(
					id = transcriptId, recordingId = recordingId, source = TranscriptSource.CLOUD,
					provenance = Provenance(engine = "cloud-live", providerType = ProviderType.OPENAI_COMPATIBLE, endpointHost = endpointHost,
						model = transcribeModel ?: "?", audioFormat = "mp3（即時擷取 PCM）", trigger = "live"),
					createdAtMillis = now, status = if (allOk) RunStatus.SUCCESS else RunStatus.PARTIAL,
					text = spans.filter { it.covers }.joinToString("\n") { it.text.trim() },
					totalSegments = spans.size, completedSegments = spans.count { it.ok }, segments = segments,
					warnings = buildList {
						if (!allOk) add("部分片段雲端轉錄失敗、未上傳或疑似重複幻覺（原文保留在片段中），可用完整雲端轉錄重轉")
						add("即時模式：音訊來自錄音時的即時擷取，與存檔可能有極小差異")
					},
				)
			)
			if (end.keyPoints.isNotBlank()) store.saveSummary(
				SummaryRecord(
					id = "keypoints-live-$now", recordingId = recordingId, sourceTranscriptId = transcriptId,
					provenance = Provenance(engine = "live-keypoints", model = end.summaryModel ?: "?", trigger = "live"),
					createdAtMillis = now, status = RunStatus.SUCCESS, text = end.keyPoints,
				)
			)
		}.onFailure { Log.e(TAG, "save failed", it) }
	}

	fun cancel() {
		session?.cancel()
		session = null
		jobs.forEach { it.cancel() }
		_state.value = LiveCloudState()
	}

	fun release() { cancel(); scope.cancel() }
}
