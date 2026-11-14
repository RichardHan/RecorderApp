package com.eva.feature_player.cloud

import com.eva.cloudtranscribe.api.AudioPayload
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClient
import com.eva.cloudtranscribe.cleanup.CleanupIntensity
import com.eva.cloudtranscribe.cleanup.TranscriptCleanupStore
import com.eva.cloudtranscribe.model.*
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupViewModelTest {
	@get:Rule val folder = TemporaryFolder()
	@Before fun setup() = Dispatchers.setMain(UnconfinedTestDispatcher())
	@After fun teardown() = Dispatchers.resetMain()

	private val record = TranscriptRecord("run", 9, TranscriptSource.CLOUD, Provenance("cloud", model = "m"), 1, updatedAtMillis = 3,
		status = RunStatus.SUCCESS, segments = listOf(
			SegmentResult(0, 0, 1000, SegmentStatus.OK, "嗯 我們 我們開始"),
			SegmentResult(1, 1000, 2000, SegmentStatus.OK, "呃 下週交付"),
		))

	private fun vm(answer: () -> CallOutcome, key: String? = "test-key", calls: AtomicInteger = AtomicInteger()): Triple<CleanupViewModel, TranscriptStore, TranscriptCleanupStore> {
		val store = TranscriptStore(folder.root).apply { saveTranscript(record) }
		val cleanups = TranscriptCleanupStore(folder.root)
		val settings = mockk<CloudSettingsRepository>()
		coEvery { settings.current() } returns CloudSettings(baseUrl = "https://example.invalid", summaryModel = "summary-m")
		val keys = mockk<ApiKeyStore>()
		every { keys.read() } returns key
		val client = object : CloudAiClient {
			override fun transcribe(prompt: String, audio: AudioPayload) = error("audio must never be sent")
			override fun complete(prompt: String): CallOutcome { calls.incrementAndGet(); return answer() }
		}
		return Triple(CleanupViewModel(settings, keys, store, cleanups) { _, _ -> client }, store, cleanups)
	}

	private fun waitUntil(check: () -> Boolean) {
		val end = System.currentTimeMillis() + 5_000
		while (!check()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(10) }
	}

	@Test fun validAnswerIsStoredSeparatelyAndOriginalIsUntouched() {
		val (vm, store, cleanups) = vm({ CallOutcome.Text("""[{"id":0,"text":"我們開始。"},{"id":1,"text":"下週交付。"}]""") })
		vm.bind(9)
		waitUntil { vm.sentences.size == 2 }
		assertNull(vm.blockedReason)
		vm.generate()
		waitUntil { vm.cleanup != null }
		assertEquals(listOf("我們開始。", "下週交付。"), vm.cleanup!!.sentences.map { it.text })
		assertEquals("run@3", vm.cleanup!!.sourceRevision)
		assertEquals(record, store.transcript(9, "run"))
		assertNotNull(cleanups.cleanup(9, CleanupIntensity.LOW))
		assertEquals(listOf(0L, 1000L), cleanupRows(vm.cleanup!!, vm.sentences).map { it.startMs })
	}

	@Test fun mismatchedAnswerStoresNothing() {
		val (vm, _, cleanups) = vm({ CallOutcome.Text("""[{"id":0,"text":"我們開始。"}]""") })
		vm.bind(9)
		waitUntil { vm.sentences.size == 2 }
		vm.generate()
		waitUntil { !vm.generating && vm.message != null }
		assertTrue(vm.message!!.contains("整次未儲存"))
		assertNull(cleanups.cleanup(9, CleanupIntensity.LOW))
		assertNull(vm.cleanup)
	}

	@Test fun withoutKeyNothingIsSent() {
		val calls = AtomicInteger()
		val (vm, _, _) = vm({ CallOutcome.Text("[]") }, key = null, calls = calls)
		vm.bind(9)
		waitUntil { vm.blockedReason != null }
		vm.generate()
		Thread.sleep(100)
		assertEquals(0, calls.get())
	}

	@Test fun changedTranscriptMarksStoredCleanupStale() {
		val (vm, store, _) = vm({ CallOutcome.Text("""[{"id":0,"text":"我們開始。"},{"id":1,"text":"下週交付。"}]""") })
		vm.bind(9)
		waitUntil { vm.sentences.size == 2 }
		vm.generate()
		waitUntil { vm.cleanup != null }
		store.saveTranscript(record.copy(updatedAtMillis = 99))
		vm.bind(9, force = true)
		waitUntil { vm.possiblyStale }
	}
}
