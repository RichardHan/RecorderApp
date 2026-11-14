package com.eva.recorder.data.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import com.eva.cloudtranscribe.api.HttpResponse
import com.eva.cloudtranscribe.api.HttpTransport
import com.eva.cloudtranscribe.live.LiveCloudPhase
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.live.LiveCloudSettingsRepository
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.ProviderType
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.secure.SummaryApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.datastore.domain.models.RecorderAudioSettings
import com.eva.datastore.domain.repository.RecorderAudioSettingsRepo
import com.eva.location.domain.repository.LocationProvider
import com.eva.recorder.data.recorder.LiveCloudCoordinator
import com.eva.recorder.data.recorder.VoiceRecorderImpl
import com.eva.recorder.domain.models.RecorderState
import com.eva.recorder.domain.recorder.AudioByteDataProvider
import com.eva.recorder.domain.recorder.AudioDataReader
import com.eva.recorder.domain.recorder.LiveTranscript
import com.eva.recorder.domain.recorder.LiveTranscriptState
import com.eva.recorder.domain.recorder.TranscriptionProvider
import com.eva.recorder.domain.recorder.VoiceRecorder
import com.eva.recorder.domain.stopwatch.RecorderStopWatch
import com.eva.recordings.domain.provider.RecorderFileProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecorderStartupAndDuplicateStartTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	@After
	fun tearDown() {
		coordinatorScope.cancel()
	}

	@Test
	fun `liveCloud subscribes before PCM capture begins so initial replay-zero frames are captured and saved`() = runBlocking {
		val pcmSource = ReplayZeroAudioSource()
		var activeWhenCaptureBegan = false
		val harness = SessionHarness(
			pcmSource = pcmSource,
			startupSettingsDelayMs = 80L,
		)
		harness.recorder.onCaptureBegan = {
			activeWhenCaptureBegan = harness.liveCloud.state.value.active
			// Emit the very first audio frames immediately as capture goes live (~1.5s >= MIN_FLUSH_SAMPLES)
			pcmSource.emitPcm(ShortArray(24_000) { 1_200 })
		}

		val started = harness.coordinator.startRecording()

		assertTrue(started)
		assertTrue(activeWhenCaptureBegan, "LiveCloudSession must be subscribed BEFORE PCM capture begins")

		harness.recorder.nextRecordingId.set(101L)
		harness.coordinator.stopRecording()

		val saved = harness.transcriptStore.transcripts(101L)
		val cloudRecord = saved.firstOrNull { it.source == TranscriptSource.CLOUD }
		assertTrue(cloudRecord != null, "Expected persisted CLOUD transcript from initial replay=0 audio chunk")
		assertEquals("cloud chunk 1", cloudRecord.text)
		assertEquals(1, harness.cloudRequestCount.get())
	}

	@Test
	fun `stopRecording racing slow liveCloud startup waits for session detachment and persists transcript`() = runBlocking {
		val settingsGate = CompletableDeferred<Unit>()
		val pcmSource = ReplayZeroAudioSource()
		val harness = SessionHarness(
			pcmSource = pcmSource,
			onBeforeReturnLiveSettings = { settingsGate.await() },
		)
		harness.recorder.nextRecordingId.set(202L)
		harness.recorder.onCaptureBegan = {
			pcmSource.emitPcm(ShortArray(24_000) { 1_500 })
		}

		val startDeferred = async(Dispatchers.Default) {
			harness.coordinator.startRecording()
		}

		// Wait until liveCloud.start() is suspended inside settings read, then issue stopRecording concurrently
		harness.enteredLiveSettingsRead.await()
		val stopDeferred = async(Dispatchers.Default) {
			harness.coordinator.stopRecording()
		}

		delay(30L)
		assertFalse(stopDeferred.isCompleted, "stopRecording must serialize behind in-flight startRecording")

		// Unblock slow settings read so start finishes, emits initial PCM, and hands off to waiting stopRecording
		settingsGate.complete(Unit)

		assertTrue(startDeferred.await())
		stopDeferred.await()

		assertEquals(LiveCloudPhase.DONE, harness.liveCloud.state.value.phase)
		val saved = harness.transcriptStore.transcripts(202L)
		val cloudRecord = saved.firstOrNull { it.source == TranscriptSource.CLOUD }
		assertTrue(cloudRecord != null, "Stop racing startup must not miss LiveCloudSession persistence")
		assertEquals("cloud chunk 1", cloudRecord.text)
	}

	@Test
	fun `cancel racing slow liveCloud startup aborts session creation without leaking active session`() = runBlocking {
		val settingsGate = CompletableDeferred<Unit>()
		val pcmSource = ReplayZeroAudioSource()
		val harness = SessionHarness(
			pcmSource = pcmSource,
			onBeforeReturnLiveSettings = { settingsGate.await() },
		)

		// Exercise direct LiveCloudCoordinator start vs cancel race across suspension boundary
		val startJob = async(Dispatchers.Default) {
			harness.liveCloud.start()
		}
		harness.enteredLiveSettingsRead.await()

		harness.liveCloud.cancel()
		settingsGate.complete(Unit)
		startJob.await()

		assertFalse(harness.liveCloud.state.value.active, "Cancelled startup must never install an active session")
		pcmSource.emitPcm(ShortArray(24_000) { 1_500 })
		harness.liveCloud.finishAndSave(recordingId = 303L)
		assertTrue(harness.transcriptStore.transcripts(303L).isEmpty())
		assertEquals(0, harness.cloudRequestCount.get())
	}

	@Test
	fun `duplicate startRecording while active or paused does not reset offline transcript or liveCloud session`() = runBlocking {
		val pcmSource = ReplayZeroAudioSource()
		val harness = SessionHarness(pcmSource = pcmSource)
		harness.recorder.nextRecordingId.set(404L)

		val firstStarted = harness.coordinator.startRecording()
		assertTrue(firstStarted)
		assertEquals(1, harness.transcriber.beginNewRecordingCalls.get())
		assertEquals(1, harness.liveSettingsReadCount.get())

		// Accumulate initial offline transcript and live-cloud audio during active recording
		harness.transcriber.appendOfflineLine(0L, "First sentence before duplicate start")
		pcmSource.emitPcm(ShortArray(24_000) { 1_400 })

		// Duplicate StartRecorderAction arrives while RECORDING
		val duplicateWhileRecording = harness.coordinator.startRecording()
		assertFalse(duplicateWhileRecording, "Duplicate start while RECORDING must be ignored")
		assertEquals(1, harness.transcriber.beginNewRecordingCalls.get(), "Offline transcript must NOT be reset")
		assertEquals(1, harness.liveSettingsReadCount.get(), "LiveCloudSession must NOT be restarted")

		// Pause recording, then send another duplicate StartRecorderAction while PAUSED
		harness.coordinator.pauseRecording()
		assertEquals(RecorderState.PAUSED, harness.recorder.recorderState.value)

		val duplicateWhilePaused = harness.coordinator.startRecording()
		assertFalse(duplicateWhilePaused, "Duplicate start while PAUSED must be ignored")
		assertEquals(1, harness.transcriber.beginNewRecordingCalls.get())
		assertEquals(1, harness.liveSettingsReadCount.get())

		// Resume, add second sentence, and stop
		harness.coordinator.resumeRecording()
		harness.transcriber.appendOfflineLine(2_000L, "Second sentence after resume")

		harness.coordinator.stopRecording()

		val saved = harness.transcriptStore.transcripts(404L)
		val offlineRecord = saved.firstOrNull { it.source == TranscriptSource.OFFLINE }
		val cloudRecord = saved.firstOrNull { it.source == TranscriptSource.CLOUD }

		assertTrue(offlineRecord != null, "Offline transcript must be saved")
		assertTrue(
			offlineRecord.text.contains("First sentence before duplicate start") &&
				offlineRecord.text.contains("Second sentence after resume"),
			"Offline transcript must retain text captured prior to duplicate StartRecorderAction, got: ${offlineRecord.text}",
		)
		assertTrue(cloudRecord != null, "Live cloud transcript must be saved without reset")
		assertEquals("cloud chunk 1", cloudRecord.text)
	}

	@Test
	fun `concurrent duplicate startRecording calls initialize recording and transcripts exactly once`() = runBlocking {
		val pcmSource = ReplayZeroAudioSource()
		val harness = SessionHarness(
			pcmSource = pcmSource,
			startupSettingsDelayMs = 60L,
		)
		harness.recorder.nextRecordingId.set(505L)
		harness.recorder.onCaptureBegan = {
			harness.transcriber.appendOfflineLine(0L, "Kept across concurrent start")
			pcmSource.emitPcm(ShortArray(24_000) { 1_300 })
		}

		val first = async(Dispatchers.Default) { harness.coordinator.startRecording() }
		val second = async(Dispatchers.Default) { harness.coordinator.startRecording() }

		val results = listOf(first.await(), second.await())
		assertEquals(1, results.count { it }, "Exactly one concurrent startRecording call should succeed")
		assertEquals(1, harness.transcriber.beginNewRecordingCalls.get())
		assertEquals(1, harness.liveSettingsReadCount.get())

		harness.coordinator.stopRecording()

		val saved = harness.transcriptStore.transcripts(505L)
		val offlineRecord = saved.firstOrNull { it.source == TranscriptSource.OFFLINE }
		val cloudRecord = saved.firstOrNull { it.source == TranscriptSource.CLOUD }
		assertTrue(offlineRecord != null && offlineRecord.text.contains("Kept across concurrent start"))
		assertTrue(cloudRecord != null && cloudRecord.text == "cloud chunk 1")
	}

	@Test
	fun `voiceRecorderImpl returns false and skips onBeforeCapture when audio permission is denied`() = runBlocking {
		val context = mockk<Context>(relaxed = true) {
			every { checkPermission(Manifest.permission.RECORD_AUDIO, any(), any()) } returns PackageManager.PERMISSION_DENIED
		}
		val stopWatch = RecorderStopWatch()
		val fileProvider = mockk<RecorderFileProvider>(relaxed = true)
		val audioSettingsRepo = mockk<RecorderAudioSettingsRepo>(relaxed = true)
		val locationProvider = mockk<LocationProvider>(relaxed = true)
		val pcmReader = mockk<AudioDataReader>(relaxed = true)
		val mediaRecorder = mockk<MediaRecorder>(relaxed = true)

		val impl = VoiceRecorderImpl(
			context = context,
			stopWatch = stopWatch,
			fileProvider = fileProvider,
			settings = audioSettingsRepo,
			locationProvider = locationProvider,
			pcmReader = pcmReader,
			recorderFactory = { mediaRecorder },
		)

		var hookCalled = false
		val started = impl.startRecording { hookCalled = true }

		assertFalse(started)
		assertFalse(hookCalled, "onBeforeCapture must not run when createRecorder fails")
		assertEquals(RecorderState.IDLE, impl.recorderState.value)
		impl.releaseResources()
	}

	@Test
	fun `voiceRecorderImpl runs onBeforeCapture before pcm capture and rejects duplicate startRecording`() = runBlocking {
		val context = mockk<Context>(relaxed = true) {
			every { checkPermission(Manifest.permission.RECORD_AUDIO, any(), any()) } returns PackageManager.PERMISSION_GRANTED
		}
		val stopWatch = RecorderStopWatch()
		val tempAudioFile = tempFolder.newFile("rec_impl_test.m4a")
		val fileProvider = object : RecorderFileProvider {
			override suspend fun createFileForRecording(extension: String?) = tempAudioFile
			override suspend fun transferFileDataToStorage(file: java.io.File, mimeType: String): Result<Long> = Result.success(777L)
			override suspend fun deleteCreatedFile(file: java.io.File): Boolean = file.delete()
		}
		val audioSettingsRepo = mockk<RecorderAudioSettingsRepo>(relaxed = true) {
			coEvery { audioSettings() } returns RecorderAudioSettings()
		}
		val locationProvider = mockk<LocationProvider>(relaxed = true)
		val events = mutableListOf<String>()
		val pcmReader = mockk<AudioDataReader>(relaxed = true) {
			every { startRecorder() } answers { events += "pcmStart" }
		}
		val mediaRecorder = mockk<MediaRecorder>(relaxed = true) {
			every { prepare() } answers { events += "mediaPrepare" }
			every { start() } answers { events += "mediaStart" }
		}

		val impl = VoiceRecorderImpl(
			context = context,
			stopWatch = stopWatch,
			fileProvider = fileProvider,
			settings = audioSettingsRepo,
			locationProvider = locationProvider,
			pcmReader = pcmReader,
			recorderFactory = { mediaRecorder },
		)

		var hookRuns = 0
		var stateInsideHook: RecorderState? = null
		val firstStarted = impl.startRecording {
			hookRuns++
			stateInsideHook = impl.recorderState.value
			events += "onBeforeCapture"
		}

		assertTrue(firstStarted)
		assertEquals(1, hookRuns)
		assertEquals(RecorderState.PREPARING, stateInsideHook, "Recorder must still be PREPARING during onBeforeCapture")
		assertEquals(listOf("mediaPrepare", "onBeforeCapture", "pcmStart", "mediaStart"), events)
		assertEquals(RecorderState.RECORDING, impl.recorderState.value)

		// Duplicate start while active must return false without invoking onBeforeCapture
		val duplicateStarted = impl.startRecording {
			hookRuns++
		}
		assertFalse(duplicateStarted)
		assertEquals(1, hookRuns, "Duplicate startRecording must not invoke onBeforeCapture")
		assertEquals(RecorderState.RECORDING, impl.recorderState.value)

		val stopResult = impl.stopRecording()
		assertTrue(stopResult.isSuccess)
		assertEquals(777L, stopResult.getOrNull())
		impl.releaseResources()
	}

	private class ReplayZeroAudioSource : AudioByteDataProvider {
		private val _stream = MutableSharedFlow<Pair<ShortArray, Int>>(replay = 0, extraBufferCapacity = 32)
		override val stream: Flow<Pair<ShortArray, Int>> = _stream.asSharedFlow()

		suspend fun emitPcm(samples: ShortArray) {
			_stream.emit(samples to samples.size)
		}
	}

	private class FakeVoiceRecorder : VoiceRecorder {
		private val _state = MutableStateFlow(RecorderState.IDLE)
		override val recorderState: StateFlow<RecorderState> = _state.asStateFlow()
		override val recorderTimer: StateFlow<LocalTime> = MutableStateFlow(LocalTime(0, 0, 5))

		val captureActive = AtomicBoolean(false)
		val nextRecordingId = AtomicLong(999L)
		var onCaptureBegan: (suspend () -> Unit)? = null

		override suspend fun startRecording(onBeforeCapture: suspend () -> Unit): Boolean {
			if (_state.value != RecorderState.IDLE) return false
			_state.value = RecorderState.PREPARING
			onBeforeCapture()
			captureActive.set(true)
			_state.value = RecorderState.RECORDING
			onCaptureBegan?.invoke()
			return true
		}

		override suspend fun pauseRecording() {
			if (_state.value == RecorderState.RECORDING) {
				_state.value = RecorderState.PAUSED
			}
		}

		override suspend fun resumeRecording() {
			if (_state.value == RecorderState.PAUSED) {
				_state.value = RecorderState.RECORDING
			}
		}

		override suspend fun stopRecording(): Result<Long> {
			captureActive.set(false)
			_state.value = RecorderState.COMPLETED
			_state.value = RecorderState.IDLE
			return Result.success(nextRecordingId.get())
		}

		override suspend fun cancelRecording() {
			captureActive.set(false)
			_state.value = RecorderState.CANCELLED
			_state.value = RecorderState.IDLE
		}

		override fun releaseResources() = Unit
	}

	private class FakeTranscriptionProvider : TranscriptionProvider {
		private val _state = MutableStateFlow(LiveTranscriptState())
		override val transcription: Flow<LiveTranscriptState> = _state.asStateFlow()

		private val _timedLines = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
		override val offlineTimedLines: Flow<List<Pair<Long, String>>> = _timedLines.asStateFlow()

		val beginNewRecordingCalls = AtomicInteger(0)

		override suspend fun initTranscriptions() = Unit

		override suspend fun beginNewRecording() {
			beginNewRecordingCalls.incrementAndGet()
			_timedLines.value = emptyList()
			_state.value = LiveTranscriptState()
		}

		fun appendOfflineLine(startMs: Long, text: String) {
			val updated = _timedLines.value + (startMs to text)
			_timedLines.value = updated
			_state.value = _state.value.copy(finalized = updated.map { it.second })
		}

		override suspend fun finishTranscription(): LiveTranscript? {
			val lines = _timedLines.value
			if (lines.isEmpty()) return null
			return LiveTranscript(
				modelId = "vosk-small-test",
				engine = "vosk",
				text = lines.joinToString("\n") { (_, text) -> "[00:00] $text" },
				decodedSegments = lines.size,
				failedSegments = 0,
				droppedMillis = 0L,
				complete = true,
			)
		}

		override fun transcriptCleanUp() {
			_timedLines.value = emptyList()
			_state.value = LiveTranscriptState()
		}
	}

	private inner class SessionHarness(
		pcmSource: AudioByteDataProvider,
		private val startupSettingsDelayMs: Long = 0L,
		private val onBeforeReturnLiveSettings: (suspend () -> Unit)? = null,
	) {
		val recorder = FakeVoiceRecorder()
		val transcriber = FakeTranscriptionProvider()
		val transcriptStore = TranscriptStore(tempFolder.newFolder())
		val cloudRequestCount = AtomicInteger(0)
		val liveSettingsReadCount = AtomicInteger(0)
		val enteredLiveSettingsRead = CompletableDeferred<Unit>()

		private val liveRepo = mockk<LiveCloudSettingsRepository> {
			coEvery { current() } coAnswers {
				liveSettingsReadCount.incrementAndGet()
				enteredLiveSettingsRead.complete(Unit)
				if (startupSettingsDelayMs > 0L) delay(startupSettingsDelayMs)
				onBeforeReturnLiveSettings?.invoke()
				LiveCloudSettings(
					enabled = true,
					consentAcceptedAtMillis = 1_700_000_000_000L,
					transcribeIntervalSec = 60,
				)
			}
		}

		private val cloudRepo = mockk<CloudSettingsRepository> {
			coEvery { current() } returns CloudSettings(
				providerType = ProviderType.OPENAI_COMPATIBLE,
				baseUrl = "https://api.example.test/v1",
				transcribeModel = "whisper-test",
			)
		}

		private val asrKeys = mockk<ApiKeyStore> {
			every { read() } returns "sk-test-asr-key"
		}

		private val summaryKeys = mockk<SummaryApiKeyStore> {
			every { read() } returns null
		}

		private val fakeTransport = HttpTransport { _, _, _, _ ->
			val idx = cloudRequestCount.incrementAndGet()
			HttpResponse(
				code = 200,
				body = """{"choices":[{"message":{"content":"cloud chunk $idx"}}]}""",
			)
		}

		val liveCloud = LiveCloudCoordinator(
			source = pcmSource,
			transcriber = transcriber,
			liveRepo = liveRepo,
			cloudRepo = cloudRepo,
			asrKeys = asrKeys,
			summaryKeys = summaryKeys,
			store = transcriptStore,
			transportFactory = { fakeTransport },
			audioEncoder = { samples -> ByteArray(samples.size * 2) { 1 } },
			scope = coordinatorScope,
		)

		val coordinator = RecorderSessionCoordinator(
			recorder = recorder,
			transcriber = transcriber,
			liveCloud = liveCloud,
			transcriptStore = transcriptStore,
		)
	}
}
