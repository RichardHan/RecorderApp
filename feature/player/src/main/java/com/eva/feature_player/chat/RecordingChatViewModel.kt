package com.eva.feature_player.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClient
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.chat.ChatEstimate
import com.eva.cloudtranscribe.chat.ChatMessageRole
import com.eva.cloudtranscribe.chat.RecordingChat
import com.eva.cloudtranscribe.chat.RecordingChatContext
import com.eva.cloudtranscribe.chat.RecordingChatMessage
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.CloudUsage
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.cloudtranscribe.usage.PeriodUsage
import com.eva.cloudtranscribe.usage.TokenUsageStore
import com.eva.cloudtranscribe.usage.boundTo
import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun interface ChatClientFactory {
	fun create(settings: CloudSettings, apiKey: String): CloudAiClient
}

@HiltViewModel
class RecordingChatViewModel internal constructor(
	private val store: TranscriptStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
	private val tokenUsageStore: TokenUsageStore,
	private val clientFactory: ChatClientFactory,
) : ViewModel() {

	@Inject
	constructor(
		store: TranscriptStore,
		settingsRepo: CloudSettingsRepository,
		keys: ApiKeyStore,
		tokenUsageStore: TokenUsageStore,
	) : this(
		store = store,
		settingsRepo = settingsRepo,
		keys = keys,
		tokenUsageStore = tokenUsageStore,
		clientFactory = ChatClientFactory { settings, apiKey ->
			CloudAiClients.create(settings, apiKey, UrlConnectionTransport(), settings.summaryModel)
		},
	)

	var loading by mutableStateOf(true)
		private set
	var messages by mutableStateOf<List<RecordingChatMessage>>(emptyList())
		private set
	var sending by mutableStateOf(false)
		private set
	var pendingQuestion by mutableStateOf<String?>(null)
		private set
	var errorMessage by mutableStateOf<String?>(null)
		private set
	var transcriptBlockedReason by mutableStateOf<String?>(null)
		private set
	var configBlockedReason by mutableStateOf<String?>(null)
		private set
	var contextInfo by mutableStateOf<RecordingChatContext?>(null)
		private set
	var estimate by mutableStateOf<ChatEstimate?>(null)
		private set
	var recordedUsage by mutableStateOf<PeriodUsage?>(null)
		private set
	var activeModel by mutableStateOf("")
		private set
	var confirmedCost by mutableStateOf(false)
		private set

	private var recordingId = -1L
	private var latestSettings: CloudSettings = CloudSettings()
	private var sourceJob: Job? = null
	private var sendJob: Job? = null

	fun bind(id: Long, force: Boolean = false) {
		if (!force && recordingId == id) return
		recordingId = id
		loading = true
		sourceJob?.cancel()
		sourceJob = viewModelScope.launch {
			combine(
				store.editChanges,
				settingsRepo.settings,
				keys.hasKey,
			) { _, settings, hasKey ->
				settings to hasKey
			}.collectLatest { (settings, hasKey) ->
				latestSettings = settings
				activeModel = settings.summaryModel
				val loaded = withContext(Dispatchers.IO) {
					val selected = runCatching {
						PlaybackTranscripts.defaultSelection(store.transcriptsWithEdits(id))
					}.getOrNull()
					val summary = selected?.let { sel ->
						val summaries = runCatching { store.summaries(id) }.getOrDefault(emptyList())
						summaries.firstOrNull {
							it.sourceTranscriptId == sel.id && RecordingChat.usableSummary(it) != null
						} ?: summaries.firstOrNull { RecordingChat.usableSummary(it) != null }
					}
					val ctx = selected?.let { sel ->
						RecordingChat.buildContext(
							transcript = sel,
							summary = summary,
							redactPii = settings.redactPiiForCloud,
						).takeIf { it.segments.isNotEmpty() }
					}
					val history = runCatching { store.chatHistory(id) }.getOrDefault(emptyList())
					val usage = runCatching {
						val events = tokenUsageStore.eventsForRecording(id)
						if (events.isEmpty()) null else CloudUsage.forRecording(id, events, settings.modelPrices)
					}.getOrNull()
					LoadedChatState(selected, summary, ctx, history, usage)
				}
				contextInfo = loaded.context
				messages = loaded.history
				transcriptBlockedReason = ChatRules.transcriptBlockedReason(loaded.transcript, loaded.context)
				configBlockedReason = ChatRules.configBlockedReason(settings, hasKey)
				recordedUsage = loaded.usage
				estimate = loaded.context?.let { ctx ->
					RecordingChat.estimateRequest(
						context = ctx,
						history = loaded.history,
						draftQuestion = "",
						model = settings.summaryModel,
						prices = settings.modelPrices,
					)
				}
				loading = false
			}
		}
	}

	fun refreshEstimate(draftQuestion: String) {
		val ctx = contextInfo ?: return
		estimate = RecordingChat.estimateRequest(
			context = ctx,
			history = messages,
			draftQuestion = draftQuestion,
			model = latestSettings.summaryModel,
			prices = latestSettings.modelPrices,
		)
	}

	fun confirmCostAndSend(question: String, onSent: () -> Unit = {}) {
		confirmedCost = true
		send(question, onSent)
	}

	fun send(question: String, onSent: () -> Unit = {}) {
		val q = question.trim().take(RecordingChat.MAX_QUESTION_CHARS)
		val ctx = contextInfo ?: return
		if (sending || q.isEmpty() || transcriptBlockedReason != null) return
		val id = recordingId
		if (id < 0L) return

		sendJob?.cancel()
		sendJob = viewModelScope.launch {
			sending = true
			pendingQuestion = q
			errorMessage = null
			try {
				val settings = settingsRepo.current()
				val key = keys.read()
				val configIssue = ChatRules.configBlockedReason(settings, !key.isNullOrBlank())
				configBlockedReason = configIssue
				if (configIssue != null || key.isNullOrBlank()) {
					errorMessage = configIssue
					return@launch
				}

				val client = clientFactory.create(settings, key).boundTo(id)
				val chatMessages = RecordingChat.buildMessages(messages, q)
				val sysPrompt = RecordingChat.systemPrompt(ctx)
				val outcome = withContext(Dispatchers.IO) {
					client.completeChat(chatMessages, sysPrompt)
				}
				when (outcome) {
					is CallOutcome.Text -> {
						val now = System.currentTimeMillis()
						val pair = ChatRules.turnPair(
							recordingId = id,
							transcriptId = ctx.transcriptId,
							question = q,
							rawAnswer = outcome.text,
							context = ctx,
							nowMillis = now,
							model = settings.summaryModel,
						)
						val updated = messages + pair.first + pair.second
						val refreshedUsage = withContext(Dispatchers.IO) {
							store.saveChatHistory(id, updated)
							val events = tokenUsageStore.eventsForRecording(id)
							if (events.isEmpty()) null else CloudUsage.forRecording(id, events, settings.modelPrices)
						}
						messages = updated
						confirmedCost = true
						recordedUsage = refreshedUsage
						estimate = RecordingChat.estimateRequest(
							context = ctx,
							history = updated,
							draftQuestion = "",
							model = settings.summaryModel,
							prices = settings.modelPrices,
						)
						onSent()
					}
					is CallOutcome.Failure -> {
						errorMessage = UiStrings.get(
							StringsRes.string.feature_player_summary_service_failed,
							"提問失敗（摘要服務）：%1\$s",
							outcome.message,
						)
					}
					CallOutcome.ContentFiltered -> {
						errorMessage = UiStrings.get(
							StringsRes.string.feature_player_content_filtered_no_answer_available,
							"內容被服務過濾，沒有回答",
						)
					}
					CallOutcome.Empty -> {
						errorMessage = UiStrings.get(
							StringsRes.string.feature_player_empty_service_response,
							"服務回應為空",
						)
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				errorMessage = UiStrings.get(
					StringsRes.string.feature_player_question_failed,
					"提問失敗：%1\$s",
					e.message ?: e.javaClass.simpleName,
				)
			} finally {
				sending = false
				pendingQuestion = null
			}
		}
	}

	fun cancel() {
		sendJob?.cancel()
		sendJob = null
		sending = false
		pendingQuestion = null
		errorMessage = UiStrings.get(
			StringsRes.string.feature_player_canceled_nothing_was_saved,
			"已取消，未儲存",
		)
	}

	fun clearHistory() {
		val id = recordingId
		if (id < 0L || sending) return
		viewModelScope.launch {
			withContext(Dispatchers.IO) { store.clearChatHistory(id) }
			messages = emptyList()
			errorMessage = null
			contextInfo?.let { ctx ->
				estimate = RecordingChat.estimateRequest(
					context = ctx,
					history = emptyList(),
					draftQuestion = "",
					model = latestSettings.summaryModel,
					prices = latestSettings.modelPrices,
				)
			}
		}
	}

	fun dismissError() {
		errorMessage = null
	}
}

private data class LoadedChatState(
	val transcript: TranscriptRecord?,
	val summary: SummaryRecord?,
	val context: RecordingChatContext?,
	val history: List<RecordingChatMessage>,
	val usage: PeriodUsage?,
)

/** Pure rules, testable without Android. */
internal object ChatRules {

	fun transcriptBlockedReason(transcript: TranscriptRecord?, context: RecordingChatContext?): String? = when {
		transcript == null -> UiStrings.get(
			StringsRes.string.feature_player_no_transcript_available_for_this_recording,
			"這段錄音還沒有可用的逐字稿",
		)
		context == null || context.segments.isEmpty() -> UiStrings.get(
			StringsRes.string.feature_player_no_usable_transcript_available_for_questions,
			"逐字稿是空的或全部失敗，無法提問",
		)
		else -> null
	}

	fun configBlockedReason(settings: CloudSettings, hasKey: Boolean): String? = when {
		settings.baseUrl.isBlank() -> UiStrings.get(
			StringsRes.string.feature_player_chat_missing_endpoint,
			"尚未設定雲端 API Endpoint",
		)
		!settings.baseUrl.trim().startsWith("https://", ignoreCase = true) -> UiStrings.get(
			StringsRes.string.feature_player_chat_invalid_endpoint,
			"雲端 API Endpoint 必須使用 https://",
		)
		settings.summaryModel.isBlank() -> UiStrings.get(
			StringsRes.string.feature_player_chat_missing_model,
			"尚未設定摘要模型",
		)
		!hasKey -> UiStrings.get(
			StringsRes.string.feature_player_chat_missing_key,
			"尚未設定 API Key",
		)
		else -> null
	}

	fun turnPair(
		recordingId: Long,
		transcriptId: String,
		question: String,
		rawAnswer: String,
		context: RecordingChatContext,
		nowMillis: Long,
		model: String?,
	): Pair<RecordingChatMessage, RecordingChatMessage> {
		val parsed = RecordingChat.parse(rawAnswer, context)
		val userMsg = RecordingChatMessage(
			id = "chat-u-$nowMillis-${UUID.randomUUID().toString().take(8)}",
			recordingId = recordingId,
			transcriptId = transcriptId,
			role = ChatMessageRole.USER,
			text = question,
			createdAtMillis = nowMillis,
		)
		val assistantMsg = RecordingChatMessage(
			id = "chat-a-${nowMillis + 1}-${UUID.randomUUID().toString().take(8)}",
			recordingId = recordingId,
			transcriptId = transcriptId,
			role = ChatMessageRole.ASSISTANT,
			text = parsed.answer,
			citations = parsed.citations,
			unverifiedCitations = parsed.unverified,
			notFound = parsed.notFound,
			coverageNote = context.coverageNote,
			createdAtMillis = nowMillis + 1,
			model = model?.takeIf { it.isNotBlank() },
		)
		return userMsg to assistantMsg
	}
}
