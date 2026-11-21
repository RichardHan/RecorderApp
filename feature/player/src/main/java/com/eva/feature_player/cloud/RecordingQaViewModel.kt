package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.qa.QaContext
import com.eva.cloudtranscribe.qa.QaRecord
import com.eva.cloudtranscribe.qa.RecordingQa
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "問這段錄音": one paid request per question, only after the user presses send. Answers are stored as separate files
 * and never change transcripts or summaries. Errors are shown as-is and never retried automatically.
 */
@HiltViewModel
class RecordingQaViewModel @Inject constructor(
	private val store: TranscriptStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) : ViewModel() {

	var history by mutableStateOf<List<QaRecord>>(emptyList())
		private set
	var asking by mutableStateOf(false)
		private set
	var message by mutableStateOf<String?>(null)
		private set
	var blockedReason by mutableStateOf<String?>(UiStrings.get(StringsRes.string.feature_player_loading_transcript, "讀取逐字稿…"))
		private set
	/** Short description of what a question would send (shown in the confirmation dialog). */
	var scope by mutableStateOf<String?>(null)
		private set

	private var transcript: TranscriptRecord? = null
	private var context: QaContext? = null
	private var recordingId = -1L
	private var sourceJob: Job? = null
	private var job: Job? = null

	fun bind(id: Long) {
		if (recordingId == id) return
		recordingId = id
		sourceJob?.cancel()
		sourceJob = viewModelScope.launch {
			store.editChanges.collectLatest {
				withContext(Dispatchers.IO) {
					val selected = runCatching { PlaybackTranscripts.defaultSelection(store.transcriptsWithEdits(id)) }.getOrNull()
					val ctx = selected?.let { RecordingQa.context(RecordingQa.segments(PlaybackTranscripts.lines(it))) }
					transcript = selected
					context = ctx
					blockedReason = QaRules.blockedReason(selected, ctx)
					scope = if (selected != null && ctx != null) QaRules.scope(selected, ctx) else null
					history = runCatching { store.qa(id) }.getOrDefault(emptyList())
				}
			}
		}
	}

	fun dismissMessage() { message = null }

	fun ask(question: String) {
		val q = question.trim().take(RecordingQa.MAX_QUESTION_CHARS)
		val transcript = transcript ?: return
		val ctx = context ?: return
		if (asking || q.isEmpty() || blockedReason != null) return
		val id = recordingId
		job = viewModelScope.launch {
			asking = true
			message = null
			try {
				val settings = settingsRepo.current()
				val key = keys.read()
				if (settings.baseUrl.isBlank() || key.isNullOrBlank()) {
					message = UiStrings.get(StringsRes.string.feature_player_set_a_cloud_summary_service_and_api, "尚未設定雲端摘要服務或 API Key")
					return@launch
				}
				val client = CloudAiClients.create(settings, key, UrlConnectionTransport(), settings.summaryModel)
				when (val outcome = withContext(Dispatchers.IO) { client.complete(RecordingQa.prompt(q, ctx)) }) {
					is CallOutcome.Text -> {
						val record = QaRules.record(id, transcript.id, q, outcome.text, ctx, System.currentTimeMillis(), settings.summaryModel)
						withContext(Dispatchers.IO) { store.saveQa(record) }
						history = listOf(record) + history
					}
					is CallOutcome.Failure -> message = UiStrings.get(StringsRes.string.feature_player_summary_service_failed, "提問失敗（摘要服務）：%1\$s", outcome.message)
					CallOutcome.ContentFiltered -> message = UiStrings.get(StringsRes.string.feature_player_content_filtered_no_answer_available, "內容被服務過濾，沒有回答")
					CallOutcome.Empty -> message = UiStrings.get(StringsRes.string.feature_player_empty_service_response, "服務回應為空")
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				message = UiStrings.get(StringsRes.string.feature_player_question_failed, "提問失敗：%1\$s", e.message ?: e.javaClass.simpleName)
			} finally {
				asking = false
			}
		}
	}

	fun cancel() {
		job?.cancel()
		job = null
		asking = false
		message = UiStrings.get(StringsRes.string.feature_player_canceled_nothing_was_saved, "已取消，未儲存")
	}

	fun delete(record: QaRecord) {
		viewModelScope.launch {
			withContext(Dispatchers.IO) { store.deleteQa(record.recordingId, record.id) }
			history = history.filterNot { it.id == record.id }
		}
	}
}

/** Pure rules, testable without Android. */
internal object QaRules {

	fun blockedReason(transcript: TranscriptRecord?, context: QaContext?): String? = when {
		transcript == null -> UiStrings.get(StringsRes.string.feature_player_no_transcript_available_for_this_recording, "這段錄音還沒有可用的逐字稿")
		context == null || context.segments.isEmpty() -> UiStrings.get(StringsRes.string.feature_player_no_usable_transcript_available_for_questions, "逐字稿是空的或全部失敗，無法提問")
		else -> null
	}

	fun scope(transcript: TranscriptRecord, context: QaContext): String =
		"${PlaybackTranscripts.label(transcript)}；" + (context.coverageNote ?: UiStrings.get(StringsRes.string.feature_player_full_transcript_segments, "全文 %1\$s 段", context.totalSegments))

	fun record(
		recordingId: Long, transcriptId: String, question: String, answer: String, context: QaContext, now: Long, model: String?,
	): QaRecord {
		val parsed = RecordingQa.parse(answer, context)
		return QaRecord(
			id = "qa-$now-${UUID.randomUUID().toString().take(8)}",
			recordingId = recordingId,
			transcriptId = transcriptId,
			question = question,
			answer = parsed.answer,
			citations = parsed.citations,
			unverifiedCitations = parsed.unverified,
			notFound = parsed.notFound,
			coverageNote = context.coverageNote,
			createdAtMillis = now,
			model = model?.takeIf { it.isNotBlank() },
		)
	}
}
