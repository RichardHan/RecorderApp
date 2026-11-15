package com.eva.feature_player.cloud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.decisions.DecisionDraft
import com.eva.cloudtranscribe.decisions.DecisionPlanner
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.models.RecordingDecisionModel
import com.eva.recordings.domain.provider.RecordingDecisionsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decisions of one recording (separate from action items). Extraction is manual and explicit: it sends the numbered
 * transcript to the user's configured summary service once, stores nothing when cancelled, failed or rejected, and
 * never takes a time from the model (sentence indices are mapped to real sentence times locally).
 */
@HiltViewModel
class DecisionsViewModel @Inject constructor(
	private val provider: RecordingDecisionsProvider,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
	private val store: TranscriptStore,
) : ViewModel() {

	var decisions by mutableStateOf<List<RecordingDecisionModel>>(emptyList())
		private set

	var extracting by mutableStateOf(false)
		private set

	var message by mutableStateOf<String?>(null)
		private set

	var possiblyStale by mutableStateOf(false)
		private set

	var canExtract by mutableStateOf(false)
		private set

	var blockedReason by mutableStateOf<String?>(null)
		private set

	private var transcript: TranscriptRecord? = null
	private var recordingId = -1L
	private var bound = false
	private var job: Job? = null

	fun bind(id: Long) {
		if (bound && recordingId == id) return
		recordingId = id
		bound = true
		viewModelScope.launch {
			withContext(Dispatchers.IO) {
				val selected = runCatching { PlaybackTranscripts.defaultSelection(store.transcripts(id)) }.getOrNull()
				transcript = selected
				val reason = DecisionsRules.blockedReason(selected)
				canExtract = reason == null
				blockedReason = reason
				if (selected != null) {
					val current = DecisionPlanner.revisionOf(selected.id, selected.updatedAtMillis)
					possiblyStale = DecisionsRules.isStale(runCatching { provider.decisions(id) }.getOrDefault(emptyList()), current)
				}
			}
			provider.decisionsAsFlow(id)
				.catch { message = "無法讀取決議：${it.message ?: "未知錯誤"}"; emit(emptyList()) }
				.collect { decisions = it }
		}
	}

	fun dismissMessage() { message = null }

	fun extract() {
		if (extracting) return
		val transcript = transcript ?: return
		val sentences = DecisionPlanner.sentences(PlaybackTranscripts.lines(transcript))
		if (sentences.isEmpty()) {
			message = "這份逐字稿沒有可用的內容"
			return
		}
		job?.cancel()
		job = viewModelScope.launch {
			extracting = true
			message = null
			try {
				val settings = settingsRepo.current()
				val key = keys.read()
				if (settings.baseUrl.isBlank() || key.isNullOrBlank()) {
					message = "尚未設定雲端摘要服務或 API Key"
					return@launch
				}
				val client = CloudAiClients.create(settings, key, UrlConnectionTransport(), settings.summaryModel)
				val outcome = withContext(Dispatchers.IO) { client.complete(DecisionPlanner.prompt(sentences)) }
				when (outcome) {
					is CallOutcome.Text -> {
						val drafts = DecisionPlanner.parse(outcome.text, sentences)
						if (drafts == null) {
							message = "回應不是有效的決議清單（格式錯誤、索引越界或含時間／說話者欄位），未儲存任何內容"
							return@launch
						}
						val revision = DecisionPlanner.revisionOf(transcript.id, transcript.updatedAtMillis)
						provider.replaceExtracted(recordingId, DecisionsRules.toModels(recordingId, drafts, revision, System.currentTimeMillis()))
						possiblyStale = false
						message = if (drafts.isEmpty()) "逐字稿裡沒有找到明確的決議" else "已抽出 ${drafts.size} 項決議"
					}
					is CallOutcome.Failure -> message = "抽出決議失敗：${outcome.message}"
					CallOutcome.ContentFiltered -> message = "內容被服務過濾，未儲存"
					CallOutcome.Empty -> message = "服務回應為空，未儲存"
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				message = "抽出決議失敗：${e.message ?: e.javaClass.simpleName}"
			} finally {
				extracting = false
			}
		}
	}

	/** Cancelling keeps the previous list and stores nothing from the cancelled run. */
	fun cancelExtraction() {
		job?.cancel()
		job = null
		extracting = false
		message = "已取消，未儲存任何決議"
	}

	fun add(text: String) {
		if (text.isBlank() || recordingId < 0) return
		viewModelScope.launch { provider.addManual(recordingId, text, System.currentTimeMillis()) }
	}

	fun edit(decision: RecordingDecisionModel, text: String) {
		viewModelScope.launch { provider.updateText(decision, text) }
	}

	fun delete(decision: RecordingDecisionModel) {
		viewModelScope.launch { provider.delete(decision) }
	}
}

/** Pure rules, testable without Android. */
internal object DecisionsRules {

	fun blockedReason(transcript: TranscriptRecord?): String? = when {
		transcript == null -> "這段錄音還沒有可用的逐字稿"
		transcript.status == RunStatus.RUNNING || transcript.status == RunStatus.QUEUED -> "逐字稿還在轉錄中，完成後才能抽出決議"
		DecisionPlanner.sentences(PlaybackTranscripts.lines(transcript)).isEmpty() -> "這份逐字稿沒有可用的內容"
		else -> null
	}

	/** Only extracted items can go stale; manual ones never do. */
	fun isStale(existing: List<RecordingDecisionModel>, currentRevision: String): Boolean =
		existing.any { it.isExtracted && it.transcriptRevision != null && it.transcriptRevision != currentRevision }

	fun toModels(recordingId: Long, drafts: List<DecisionDraft>, revision: String, now: Long) = drafts.map { draft ->
		RecordingDecisionModel(
			id = 0L,
			recordingId = recordingId,
			text = draft.text,
			startMs = draft.startMs,
			sourceSentenceIndex = draft.sourceSentenceIndex,
			source = RecordingDecisionModel.SOURCE_EXTRACTED,
			createdAtMillis = now,
			transcriptRevision = revision,
		)
	}
}
