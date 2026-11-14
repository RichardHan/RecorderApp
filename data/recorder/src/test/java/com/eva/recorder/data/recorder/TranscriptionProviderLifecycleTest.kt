package com.eva.recorder.data.recorder

import com.eva.datastore.domain.models.TranscriptionSettings
import com.eva.datastore.domain.repository.TranscriptionSettingsRepo
import com.eva.recorder.domain.recorder.AudioByteDataProvider
import com.eva.transcribe.domain.AudioTranscriptor
import com.eva.transcribe.domain.models.STTModel
import com.eva.transcribe.domain.models.TranscriptionResult
import com.eva.transcribe.domain.repository.STTModelsRepository
import com.eva.transcribe.offline.OfflineSpeechEngine
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranscriptionProviderLifecycleTest {
	private class Fixture(scope: CoroutineScope, engine: String) {
		val settings = MutableStateFlow(TranscriptionSettings(true, "model"))
		val audio = MutableSharedFlow<Pair<ShortArray, Int>>()
		val transcriptor = mockk<AudioTranscriptor>(relaxed = true)
		val offline = mockk<OfflineSpeechEngine>(relaxed = true)
		val repository = mockk<STTModelsRepository>()
		val model = STTModel("model", "model", "model", engine = engine)
		val provider: TranscriptionProviderImpl
		init {
			coEvery { repository.readModelByLanguage(any()) } returns Result.success(model)
			coEvery { offline.load(any(), any()) } returns Result.success(model)
			coEvery { transcriptor.setUp(any(), any()) } returns Result.success(Unit)
			coEvery { transcriptor.finishAudio() } returns TranscriptionResult()
			provider = TranscriptionProviderImpl(
				object : AudioByteDataProvider { override val stream = audio }, transcriptor,
				mockk<TranscriptionSettingsRepo> { every { settingsFlow } returns settings },
				repository, offline, scope,
			)
		}
	}

	@Test fun finishingThenDestroyingDoesNotReleaseAnotherConsumersModel() = runTest {
		val f = Fixture(backgroundScope, "sherpa-sensevoice")
		f.provider.initTranscriptions(); runCurrent()
		f.provider.finishTranscription()
		f.provider.transcriptCleanUp(); runCurrent()
		coVerify(exactly = 1) { f.offline.release() }
	}

	@Test fun switchingToVoskDoesNotReleaseSherpaTwice() = runTest {
		val f = Fixture(backgroundScope, "sherpa-sensevoice")
		f.provider.initTranscriptions(); runCurrent()
		coEvery { f.repository.readModelByLanguage("vosk") } returns Result.success(f.model.copy(modelId = "vosk", engine = "vosk"))
		f.settings.value = TranscriptionSettings(true, "vosk"); runCurrent()
		f.provider.finishTranscription()
		coVerify(exactly = 1) { f.offline.release() }
	}

	@Test fun stoppingImmediatelyAfterSpeechFlushesTailExactlyOnce() = runTest {
		val f = Fixture(backgroundScope, "vosk")
		coEvery { f.transcriptor.recognizeAudio(any(), any()) } returns TranscriptionResult(partial = "最後一句")
		coEvery { f.transcriptor.finishAudio() } returns TranscriptionResult(fullResult = "最後一句")
		f.provider.initTranscriptions(); runCurrent()
		f.audio.emit(shortArrayOf(1, 2) to 2); runCurrent()
		val result = f.provider.finishTranscription()!!
		assertEquals("最後一句", result.text)
		assertTrue(result.complete)
		coVerifyOrder { f.transcriptor.recognizeAudio(any(), any()); f.transcriptor.finishAudio() }
		coVerify(exactly = 1) { f.transcriptor.finishAudio() }
	}

	@Test fun failingToFlushDoesNotReportSuccess() = runTest {
		val f = Fixture(backgroundScope, "vosk")
		coEvery { f.transcriptor.finishAudio() } throws IllegalStateException("flush failed")
		f.provider.initTranscriptions(); runCurrent()
		val result = f.provider.finishTranscription()!!
		assertFalse(result.complete)
		assertEquals(1, result.failedSegments)
	}
}
