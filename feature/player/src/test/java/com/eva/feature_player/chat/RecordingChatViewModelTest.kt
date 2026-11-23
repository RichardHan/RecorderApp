package com.eva.feature_player.chat

import com.eva.cloudtranscribe.api.AudioPayload
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.ChatMessage
import com.eva.cloudtranscribe.api.ChatRole
import com.eva.cloudtranscribe.api.CloudAiClient
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SegmentStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.cloudtranscribe.usage.ModelPrice
import com.eva.cloudtranscribe.usage.TokenUsage
import com.eva.cloudtranscribe.usage.TokenUsageHooks
import com.eva.cloudtranscribe.usage.TokenUsageStore
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingChatViewModelTest {

	@get:Rule
	val folder = TemporaryFolder()
	private val viewModels = ViewModelStore()

	@Before
	fun setup() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		TokenUsageHooks.store = null
	}

	@After
	fun teardown() {
		viewModels.clear()
		TokenUsageHooks.store = null
		Dispatchers.resetMain()
	}

	private val sampleTranscript = TranscriptRecord(
		id = "t1",
		recordingId = 5L,
		source = TranscriptSource.CLOUD,
		provenance = Provenance("cloud", model = "gemini-3.5-transcribe"),
		createdAtMillis = 100L,
		status = RunStatus.SUCCESS,
		segments = listOf(
			SegmentResult(0, 0, 5_000, SegmentStatus.OK, "今天決定週五發布新版本"),
			SegmentResult(1, 5_000, 10_000, SegmentStatus.OK, "小王負責整理發布說明"),
		),
	)

	private val sampleSummary = SummaryRecord(
		id = "s1",
		recordingId = 5L,
		sourceTranscriptId = "t1",
		provenance = Provenance("cloud-summary", model = "gemini-3.8-flash"),
		createdAtMillis = 200L,
		status = RunStatus.SUCCESS,
		text = "- 週五發布新版本\n- 小王負責發布說明",
	)

	private fun createVm(
		withTranscript: Boolean = true,
		withSummary: Boolean = true,
		settings: CloudSettings = CloudSettings(
			baseUrl = "https://api.example/v1",
			summaryModel = "gemini-3.8-flash",
			modelPrices = listOf(ModelPrice("gemini-3.8-flash", inputPerMillion = "0.5", outputPerMillion = "1.5")),
		),
		apiKey: String? = "test-key",
		onChatCall: (List<ChatMessage>, String?) -> CallOutcome = { _, _ ->
			TokenUsageHooks.record("gemini-3.8-flash", TokenUsage(100, 20), operation = "chat")
			CallOutcome.Text("週五發布新版本〔S1〕")
		},
	): Triple<RecordingChatViewModel, TranscriptStore, TokenUsageStore> {
		val store = TranscriptStore(folder.root)
		if (withTranscript) store.saveTranscript(sampleTranscript)
		if (withSummary) store.saveSummary(sampleSummary)
		val usageStore = TokenUsageStore(folder.root)
		TokenUsageHooks.store = usageStore

		val settingsFlow = MutableStateFlow(settings)
		val hasKeyFlow = MutableStateFlow(!apiKey.isNullOrBlank())
		val settingsRepo = mockk<CloudSettingsRepository>()
		every { settingsRepo.settings } returns settingsFlow
		coEvery { settingsRepo.current() } coAnswers { settingsFlow.value }

		val keys = mockk<ApiKeyStore>()
		every { keys.hasKey } returns hasKeyFlow
		every { keys.read() } returns apiKey

		val client = object : CloudAiClient {
			override fun transcribe(prompt: String, audio: AudioPayload) = error("audio must not be sent")
			override fun complete(prompt: String) = error("expected completeChat")
			override fun completeChat(messages: List<ChatMessage>, systemPrompt: String?) = onChatCall(messages, systemPrompt)
		}
		val vm = RecordingChatViewModel(store, settingsRepo, keys, usageStore) { _, _ -> client }
		viewModels.put("chat", vm)
		return Triple(vm, store, usageStore)
	}

	private fun waitUntil(check: () -> Boolean) {
		val end = System.currentTimeMillis() + 5_000L
		while (!check()) {
			check(System.currentTimeMillis() < end) { "timed out waiting for condition" }
			Thread.sleep(10)
		}
	}

	@Test
	fun missing_transcript_blocks_chat_and_never_calls_client() {
		val calls = AtomicInteger()
		val (vm, _, _) = createVm(withTranscript = false) { _, _ ->
			calls.incrementAndGet()
			CallOutcome.Text("hi")
		}
		vm.bind(5L)
		waitUntil { !vm.loading }
		assertNotNull(vm.transcriptBlockedReason)
		vm.send("重點是什麼？")
		Thread.sleep(50)
		assertEquals(0, calls.get())
	}

	@Test
	fun missing_api_key_sets_config_blocked_reason_and_blocks_send() {
		val calls = AtomicInteger()
		val (vm, _, _) = createVm(apiKey = null) { _, _ ->
			calls.incrementAndGet()
			CallOutcome.Text("hi")
		}
		vm.bind(5L)
		waitUntil { !vm.loading }
		assertNull(vm.transcriptBlockedReason)
		assertNotNull(vm.configBlockedReason)
		vm.send("重點是什麼？")
		waitUntil { !vm.sending && vm.errorMessage != null }
		assertEquals(0, calls.get())
	}

	@Test
	fun send_persists_multi_turn_chat_records_usage_and_clears_cleanly() {
		val recordedCalls = mutableListOf<Pair<List<ChatMessage>, String?>>()
		val (vm, store, usageStore) = createVm { msgs, sys ->
			recordedCalls += msgs to sys
			TokenUsageHooks.record("gemini-3.8-flash", TokenUsage(150, 25), operation = "chat")
			if (msgs.size == 1) CallOutcome.Text("決定週五發布新版本〔S1〕")
			else CallOutcome.Text("小王負責整理發布說明〔S2〕")
		}

		vm.bind(5L)
		waitUntil { !vm.loading }
		assertNull(vm.transcriptBlockedReason)
		assertNull(vm.configBlockedReason)
		assertNotNull(vm.estimate)
		assertTrue(vm.estimate!!.promptTokens > 0L)
		assertTrue(vm.estimate!!.unitPriceConfigured)

		vm.confirmCostAndSend("什麼時候發布？")
		waitUntil { vm.messages.size == 2 && !vm.sending }
		assertEquals(listOf("什麼時候發布？", "決定週五發布新版本〔S1〕"), vm.messages.map { it.text })
		assertEquals(listOf(0L), vm.messages[1].citations.map { it.startMs })

		// Follow-up turn passes prior history
		vm.send("誰負責發布說明？")
		waitUntil { vm.messages.size == 4 && !vm.sending }
		assertEquals(3, recordedCalls[1].first.size)
		assertTrue(recordedCalls[1].second!!.contains("【錄音摘要／重點】"))

		// Persisted in store and token usage recorded for recordingId = 5L
		assertEquals(4, store.chatHistory(5L).size)
		assertEquals(2, usageStore.eventsForRecording(5L).size)
		assertNotNull(vm.recordedUsage)
		assertEquals(300L, vm.recordedUsage!!.promptTokens)

		// Clear removes persisted chat history
		vm.clearHistory()
		waitUntil { vm.messages.isEmpty() }
		assertTrue(store.chatHistory(5L).isEmpty())
	}

	@Test
	fun failed_response_keeps_persisted_history_untouched() {
		val (vm, store, _) = createVm { _, _ ->
			CallOutcome.Failure("HTTP 500", retryable = true, httpCode = 500)
		}
		vm.bind(5L)
		waitUntil { !vm.loading }
		vm.confirmCostAndSend("重點是什麼？")
		waitUntil { !vm.sending && vm.errorMessage != null }
		assertTrue(vm.messages.isEmpty())
		assertTrue(store.chatHistory(5L).isEmpty())
		assertTrue(vm.errorMessage!!.contains("HTTP 500"))
	}
}
