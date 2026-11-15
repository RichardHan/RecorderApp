package com.eva.recorder.data.recorder

import android.util.Log
import com.eva.cloudtranscribe.api.AudioPayload
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.HttpTransport
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "LIVE_CLOUD"

internal class DetachedCloudFinish(
	private val coordinator: LiveCloudCoordinator,
	private val session: LiveCloudSession,
	private val ownJobs: List<Job>,
	private val model: String?,
	private val host: String?,
) {
	suspend fun finishAndSave(recordingId: Long) {
		coordinator.completeDetachedFinish(
			recordingId = recordingId,
			s = session,
			ownJobs = ownJobs,
			model = model,
			host = host,
		)
	}
}

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
	private val transportFactory: () -> HttpTransport = { UrlConnectionTransport() },
	private val audioEncoder: suspend (ShortArray) -> ByteArray = { samples ->
		withContext(Dispatchers.Default) { Mp3Encoder.encode(samples, 16_000) }
	},
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
	private val _state = MutableStateFlow(LiveCloudState())
	val state: StateFlow<LiveCloudState> = _state.asStateFlow()

	private var session: LiveCloudSession? = null
	private var jobs = mutableListOf<Job>()
	private val lock = Any()
	private val startupLock = Mutex()
	private var generation = 0L
	/** Session of a stopped recording still flushing/saving; shown in the UI only until a newer recording starts. */
	private var finishing: LiveCloudSession? = null
	private var transcribeModel: String? = null
	private var endpointHost: String? = null

	suspend fun start() {
		// a new recording always starts from a clean state; a stale/unfinished earlier session is dropped (not saved)
		val startGeneration = synchronized(lock) {
			generation += 1L
			// an unfinished session that was never stopped (e.g. service reused without stop) is dropped; a session that
			// is already finishing for a stopped recording is NOT touched — it keeps flushing and saving its own results
			session?.cancel()
			session = null
			jobs.forEach { it.cancel() }
			jobs = mutableListOf()
			finishing = null
			_state.value = LiveCloudState()
			generation
		}
		startupLock.withLock {
			synchronized(lock) { if (generation != startGeneration) return }
			val live = liveRepo.current()
			synchronized(lock) { if (generation != startGeneration) return }
			if (!live.isActive) return // default: OFF, nothing happens
			val cloud = cloudRepo.current()
			val asrKey = asrKeys.read()
			synchronized(lock) { if (generation != startGeneration) return }
			cloud.validate(asrKey != null)?.let { problem ->
				synchronized(lock) {
					if (generation == startGeneration) {
						_state.value = LiveCloudState(
							active = true,
							phase = LiveCloudPhase.DONE,
							transcribeError = "雲端轉錄未啟動：$problem",
						)
					}
				}
				return
			}
			val explicitSummaryKey = summaryKeys.read()
			synchronized(lock) { if (generation != startGeneration) return }
			val summaryKey = if (live.summaryReuseTranscribeKey) asrKey else explicitSummaryKey
			val summaryProblem = live.validateSummary(explicitSummaryKey != null, asrKey != null)
			val transport = transportFactory()
			val asrClient = CloudAiClients.create(cloud, asrKey!!, transport, cloud.transcribeModel)
			// snapshot per session: vocabulary edits apply from the next recording, never mid-session
			val transcribePrompt = Prompts.withVocabulary(Prompts.TRANSCRIBE, cloud.customVocabulary)
			val summaryClient = if (summaryProblem == null && summaryKey != null)
				OpenAiCompatibleClient(live.summaryBaseUrl, summaryKey, live.summaryModel, ReasoningEffort.OFF, transport)
			else null

			val s = LiveCloudSession(
				scope = scope,
				settings = live,
				sessionId = UUID.randomUUID().toString(),
				transcribe = { samples, _, _ ->
					val mp3 = audioEncoder(samples)
					asrClient.transcribe(transcribePrompt, AudioPayload(mp3, "mp3"))
				},
				summarize = { prompt ->
					summaryClient?.complete(prompt) ?: CallOutcome.Failure("重點整理未啟動：${summaryProblem ?: "缺少摘要 Key"}", retryable = false)
				},
				transcribeModel = cloud.transcribeModel,
			)
			val sessionJobs = mutableListOf<Job>()
			val installed = synchronized(lock) {
				if (generation != startGeneration) {
					false
				} else {
					session = s
					jobs = sessionJobs
					transcribeModel = cloud.transcribeModel
					endpointHost = Redactor.hostOf(cloud.baseUrl)
					true
				}
			}
			if (!installed) {
				s.cancel()
				return
			}
			// CoroutineStart.UNDISPATCHED attaches collectors to the replay-0 PCM stream synchronously
			// before start() returns, preventing initial audio frame loss when capture starts immediately.
			sessionJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
				s.state.collect { st ->
					synchronized(lock) {
						if (com.eva.cloudtranscribe.live.LiveCloudOwnership.mayPublish(s, session, finishing)) _state.value = st
					}
				}
			}
			sessionJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
				source.stream.collect { (buffer, size) -> if (size > 0) s.onAudio(buffer, size) }
			}
			sessionJobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
				transcriber.offlineTimedLines.collect { l -> s.onOfflineLines(l.map { (ms, t) -> OfflineLine(ms, t) }) }
			}
			Log.i(TAG, "live cloud session started")
		}
	}

	/**
	 * Waits for any in-flight [start] to finish assigning its session, then detaches the active session so a
	 * subsequent recording's [start] can proceed without waiting on network flush/summary persistence.
	 */
	suspend fun detachForFinish(): DetachedCloudFinish? = startupLock.withLock {
		val s: LiveCloudSession
		val ownJobs: List<Job>
		val model: String?
		val host: String?
		synchronized(lock) {
			s = session ?: return@withLock null
			ownJobs = jobs
			model = transcribeModel
			host = endpointHost
			// detach immediately: a new recording's start() can neither cancel nor be overwritten by this session
			session = null
			jobs = mutableListOf()
			finishing = s
		}
		ownJobs.filterIndexed { i, _ -> i != 0 }.forEach { it.cancel() } // stop audio/offline feeds, keep UI mirror
		DetachedCloudFinish(this, s, ownJobs, model, host)
	}

	/** Flush + wait (bounded) and persist results as NEW immutable records; never touches audio or other transcripts. */
	suspend fun finishAndSave(recordingId: Long) {
		detachForFinish()?.finishAndSave(recordingId)
	}

	internal suspend fun completeDetachedFinish(
		recordingId: Long,
		s: LiveCloudSession,
		ownJobs: List<Job>,
		model: String?,
		host: String?,
	) {
		val end = try {
			s.finish()
		} catch (e: CancellationException) {
			ownJobs.forEach { it.cancel() }
			synchronized(lock) { if (finishing === s) finishing = null }
			throw e
		} catch (e: Exception) {
			Log.e(TAG, "finish failed", e)
			ownJobs.forEach { it.cancel() }
			synchronized(lock) { if (finishing === s) finishing = null }
			return
		}
		ownJobs.forEach { it.cancel() }
		synchronized(lock) {
			if (com.eva.cloudtranscribe.live.LiveCloudOwnership.mayPublish(s, session, finishing)) {
				_state.value = end
			}
			if (finishing === s) finishing = null
		}
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
					provenance = Provenance(engine = "cloud-live", providerType = ProviderType.OPENAI_COMPATIBLE, endpointHost = host,
						model = model ?: "?", audioFormat = "mp3（即時擷取 PCM）", trigger = "live"),
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

	/** Cancels only the current active/starting session without disturbing an earlier detached finishing session. */
	fun cancelActiveSession() = synchronized(lock) {
		generation += 1L
		session?.cancel()
		session = null
		jobs.forEach { it.cancel() }
		jobs = mutableListOf()
		if (finishing == null) {
			_state.value = LiveCloudState()
		}
	}

	fun cancel() = synchronized(lock) {
		generation += 1L
		finishing = null
		session?.cancel()
		session = null
		jobs.forEach { it.cancel() }
		jobs = mutableListOf()
		_state.value = LiveCloudState()
	}

	fun release() { cancel(); scope.cancel() }
}

