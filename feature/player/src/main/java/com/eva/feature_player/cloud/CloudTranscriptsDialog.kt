package com.eva.feature_player.cloud

import com.eva.transcribe.domain.models.localizedName

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryFormat
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.cloudtranscribe.work.CloudJobScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CloudUiState(
	val transcripts: List<TranscriptRecord> = emptyList(),
	val summaries: List<SummaryRecord> = emptyList(),
)

@HiltViewModel
class CloudTranscriptsViewModel @Inject constructor(
	private val store: TranscriptStore,
	private val scheduler: CloudJobScheduler,
	private val offline: com.eva.cloudtranscribe.work.OfflineFileTranscriber,
) : ViewModel() {
	val offlineModels = offline.availableModels.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

	fun startOffline(model: com.eva.transcribe.domain.models.STTModel) = viewModelScope.launch {
		withContext(Dispatchers.IO) { offline.start(recordingId, model) }
		_messages.emit(UiStrings.get(StringsRes.string.feature_player_offline_transcription_queued_audio_stays_on_this, "已排入離線轉錄（%1\$s），音訊不會上傳", model.localizedName()))
		refresh()
	}

	private val _state = MutableStateFlow(CloudUiState())
	val state = _state.asStateFlow()
	private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
	val messages = _messages.asSharedFlow()
	private var recordingId = -1L

	fun bind(id: Long) {
		if (recordingId == id) return
		recordingId = id
		viewModelScope.launch {
			while (isActive) { refresh(); delay(1500) }
		}
	}

	private suspend fun refresh() = withContext(Dispatchers.IO) {
		_state.value = CloudUiState(store.transcriptsWithEdits(recordingId), store.summaries(recordingId))
	}

	fun start(resume: String? = null) = viewModelScope.launch {
		scheduler.startTranscription(recordingId, resume)
			.onSuccess { _messages.emit(UiStrings.get(StringsRes.string.feature_player_cloud_transcription_queued, "已排入雲端轉錄")) }
			.onFailure { _messages.emit(it.message ?: UiStrings.get(StringsRes.string.feature_player_could_not_start, "無法開始")) }
		refresh()
	}

	fun cancel(id: String) = scheduler.cancel(id)
	fun summarize(transcriptId: String) = viewModelScope.launch {
		scheduler.startSummary(recordingId, transcriptId).onFailure { _messages.emit(it.message ?: UiStrings.get(StringsRes.string.feature_player_could_not_summarize, "無法摘要")) }.onSuccess { _messages.emit(UiStrings.get(StringsRes.string.feature_player_summary_queued, "已排入摘要")) }
		refresh()
	}

	/** Another format for the same transcript: always a NEW summary record, the old one is left untouched. */
	fun regenerateSummary(transcriptId: String, format: SummaryFormat) = viewModelScope.launch {
		scheduler.startSummary(recordingId, transcriptId, format)
			.onFailure { _messages.emit(it.message ?: UiStrings.get(StringsRes.string.feature_player_could_not_summarize, "無法摘要")) }
			.onSuccess { _messages.emit(UiStrings.get(StringsRes.string.feature_player_new_summary_queued_using, "已用「%1\$s」排入新摘要", format.zh())) }
		refresh()
	}
	fun deleteTranscript(id: String) = viewModelScope.launch(Dispatchers.IO) { scheduler.cancel(id); store.deleteTranscript(recordingId, id); refresh() }
	fun deleteSummary(id: String) = viewModelScope.launch(Dispatchers.IO) { store.deleteSummary(recordingId, id); refresh() }
}

private val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

private fun TranscriptRecord.label() = "${if (source == TranscriptSource.OFFLINE) (if (provenance.trigger == "live") UiStrings.get(StringsRes.string.feature_player_live_offline, "離線即時") else UiStrings.get(StringsRes.string.feature_player_offline, "離線")) else UiStrings.get(StringsRes.string.feature_player_cloud, "雲端")}・${provenance.model}・${fmt.format(Date(createdAtMillis))}"

private fun SummaryFormat.zh() = when (this) {
	SummaryFormat.CONCISE -> UiStrings.get(StringsRes.string.feature_player_concise, "精簡")
	SummaryFormat.DECISIONS_AND_ACTIONS -> UiStrings.get(StringsRes.string.feature_player_decisions_and_action_items, "決議與待辦")
	SummaryFormat.BULLETS_AND_OPEN_QUESTIONS -> UiStrings.get(StringsRes.string.feature_player_bullets_and_open_questions, "條列＋未解問題")
	SummaryFormat.CUSTOM -> UiStrings.get(StringsRes.string.feature_player_custom, "自訂")
}

private fun RunStatus.zh() = when (this) {
	RunStatus.QUEUED -> UiStrings.get(StringsRes.string.feature_player_queued, "排隊中"); RunStatus.RUNNING -> UiStrings.get(StringsRes.string.feature_player_in_progress, "處理中"); RunStatus.SUCCESS -> UiStrings.get(StringsRes.string.feature_player_complete, "完成")
	RunStatus.PARTIAL -> UiStrings.get(StringsRes.string.feature_player_partially_complete, "部分完成"); RunStatus.FAILED -> UiStrings.get(StringsRes.string.feature_player_failed, "失敗"); RunStatus.CANCELLED -> UiStrings.get(StringsRes.string.feature_player_canceled, "已取消")
}

@Composable
fun CloudTranscriptsDialog(
	recordingId: Long,
	onDismiss: () -> Unit,
	onSeekTo: ((Long) -> Unit)? = null,
	viewModel: CloudTranscriptsViewModel = hiltViewModel(),
) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	val state by viewModel.state.collectAsStateWithLifecycle()
	val offlineModels by viewModel.offlineModels.collectAsStateWithLifecycle()
	var offlinePick by remember { mutableStateOf<String?>(null) }
	var confirmUpload by remember { mutableStateOf(false) }
	var confirmDelete by remember { mutableStateOf<String?>(null) }
	var regenerateFor by remember { mutableStateOf<String?>(null) }
	var compare by remember { mutableStateOf(listOf<String>()) }
	var message by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(viewModel) { viewModel.messages.collect { message = it } }

	Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		Surface(Modifier.fillMaxSize()) {
			LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
				item {
					Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
						Text(stringResource(StringsRes.string.feature_player_transcripts_cloud_transcription_and_summaries), style = MaterialTheme.typography.titleLarge)
						TextButton(onDismiss) { Text(stringResource(StringsRes.string.feature_player_close)) }
					}
					Text(stringResource(StringsRes.string.feature_player_each_cloud_transcription_is_saved_separately_from), style = MaterialTheme.typography.bodySmall)
					Text(stringResource(StringsRes.string.feature_player_transcribe_on_device_no_upload), style = MaterialTheme.typography.titleSmall)
					if (offlineModels.isEmpty()) Text(stringResource(StringsRes.string.feature_player_download_an_offline_model_in_settings_transcription), style = MaterialTheme.typography.bodySmall)
					else {
						Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
							offlineModels.forEach { m -> FilterChip(offlinePick == m.modelId, { offlinePick = m.modelId }, { Text(m.localizedName().substringBefore('（')) }) }
						}
						OutlinedButton(enabled = offlinePick != null, onClick = {
							offlineModels.firstOrNull { it.modelId == offlinePick }?.let(viewModel::startOffline)
						}, modifier = Modifier.fillMaxWidth()) { Text(stringResource(StringsRes.string.feature_player_transcribe_on_device)) }
					}
					Button(onClick = { confirmUpload = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(StringsRes.string.feature_player_transcribe_in_the_cloud)) }
					message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
				}
				if (compare.size == 2) item {
					val a = state.transcripts.firstOrNull { it.id == compare[0] }
					val b = state.transcripts.firstOrNull { it.id == compare[1] }
					if (a != null && b != null) Card {
						Column(Modifier.padding(8.dp)) {
							Text(stringResource(StringsRes.string.feature_player_compare_side_by_side), style = MaterialTheme.typography.titleMedium)
							Row(Modifier.fillMaxWidth().heightIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
								listOf(a, b).forEach { t ->
									Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
										Text(t.label(), style = MaterialTheme.typography.labelMedium)
										SelectionContainer { Text(t.text.ifBlank { UiStrings.get(StringsRes.string.feature_player_empty, "（無內容）") }, style = MaterialTheme.typography.bodySmall) }
									}
								}
							}
						}
					}
				}
				if (state.transcripts.isEmpty()) item { Text(stringResource(StringsRes.string.feature_player_no_transcripts_yet)) }
				items(state.transcripts, key = { it.id }) { t ->
					Card {
						Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							Text(t.label(), style = MaterialTheme.typography.titleSmall)
							Text(stringResource(StringsRes.string.feature_player_status, (t.status.zh()).toString()) + (t.provenance.endpointHost?.let { "・$it" } ?: "") + (t.provenance.reasoningEffort?.let { UiStrings.get(StringsRes.string.feature_player_effort, "・effort=%1\$s", it) } ?: "") + UiStrings.get(StringsRes.string.feature_player_requests, "・請求 %1\$s 次・%2\$s", t.requestCount, t.provenance.trigger), style = MaterialTheme.typography.bodySmall)
							t.lastExecution?.let { run ->
								val cost = run.knownUsd?.let { "US$$it" }
									?: stringResource(StringsRes.string.transcript_cost_unreported)
								Text(stringResource(StringsRes.string.transcript_last_execution,
									run.elapsedMs / 1000.0, cost, run.pricedRequests, run.requests),
									style = MaterialTheme.typography.bodySmall)
								run.usdPerAudioHour()?.let { rate ->
									Text(stringResource(StringsRes.string.transcript_cost_per_hour, rate), style = MaterialTheme.typography.bodySmall)
								}
							}
							if (t.status == RunStatus.RUNNING || t.status == RunStatus.QUEUED) {
								if (t.totalSegments > 0) LinearProgressIndicator({ t.completedSegments / t.totalSegments.toFloat() }, Modifier.fillMaxWidth())
								else LinearProgressIndicator(Modifier.fillMaxWidth())
							}
							t.error?.let { Text(stringResource(StringsRes.string.feature_player_error, (it).toString()), color = MaterialTheme.colorScheme.error) }
							t.warnings.take(5).forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
							if (t.text.isNotBlank()) SelectionContainer { Text(t.text.take(1500) + if (t.text.length > 1500) UiStrings.get(StringsRes.string.feature_player_open_comparison_for_the_full_text, "…（比較檢視可看全文）") else "", style = MaterialTheme.typography.bodySmall) }
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								FilterChip(t.id in compare, { compare = if (t.id in compare) compare - t.id else (compare + t.id).takeLast(2) }, { Text(stringResource(StringsRes.string.feature_player_compare)) })
								if (t.status == RunStatus.RUNNING || t.status == RunStatus.QUEUED) OutlinedButton({ viewModel.cancel(t.id) }) { Text(stringResource(StringsRes.string.feature_player_cancel)) }
								if (t.source == TranscriptSource.CLOUD && t.status in setOf(RunStatus.PARTIAL, RunStatus.FAILED, RunStatus.CANCELLED)) OutlinedButton({ viewModel.start(t.id) }) { Text(stringResource(StringsRes.string.feature_player_retry_unfinished_segments)) }
							}
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								if (t.text.isNotBlank() && t.status != RunStatus.RUNNING) OutlinedButton({ viewModel.summarize(t.id) }) { Text(stringResource(StringsRes.string.feature_player_summarize_this_transcript)) }
								TextButton({ confirmDelete = "t:" + t.id }) { Text(stringResource(StringsRes.string.feature_player_delete)) }
							}
						}
					}
				}
				if (state.summaries.isNotEmpty()) item { Text(stringResource(StringsRes.string.feature_player_summary), style = MaterialTheme.typography.titleMedium) }
				item {
					val newestSummary = state.summaries.firstOrNull { it.status == RunStatus.SUCCESS && it.text.isNotBlank() }
					ActionItemsCard(
						recordingId = recordingId,
						latestSummaryText = newestSummary?.text,
						latestSummaryAtMillis = newestSummary?.createdAtMillis,
					)
				}
				item { RecordingNotesCard(recordingId = recordingId) }
				item { RecordingQaCard(recordingId = recordingId, onSeekTo = onSeekTo) }
				item {
					val newestSummary = state.summaries.firstOrNull { it.status == RunStatus.SUCCESS && it.text.isNotBlank() }
					if (newestSummary != null) OutlineCard(
						recordingId = recordingId,
						summaryId = newestSummary?.id,
						summaryText = newestSummary?.text,
					)
				}
				item {
					val accepted = state.summaries.firstOrNull { it.status == RunStatus.SUCCESS && it.text.isNotBlank() }
					FollowUpDraftCard(
						recordingId = recordingId,
						acceptedSummaryId = accepted?.id,
						acceptedSummaryText = accepted?.text,
					)
				}
				item { SpeechPaceCard(state.transcripts, onSeekTo = { ms -> onSeekTo?.invoke(ms); onDismiss() }) }
				items(state.summaries, key = { it.id }) { s ->
					Card {
						Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							val src = state.transcripts.firstOrNull { it.id == s.sourceTranscriptId }
							val storedFormat = s.provenance.summaryFormat
								?.let { name -> SummaryFormat.entries.firstOrNull { it.name == name } }
							Text(stringResource(StringsRes.string.feature_player_source, (s.provenance.model).toString(), (s.status.zh()).toString(), (storedFormat?.let { "${it.zh()}・" } ?: "").toString(), (src?.label() ?: s.sourceTranscriptId).toString()), style = MaterialTheme.typography.labelMedium)
							s.error?.let { Text(stringResource(StringsRes.string.feature_player_error, (it).toString()), color = MaterialTheme.colorScheme.error) }
							if (s.text.isNotBlank()) {
								if (s.citations.isEmpty()) SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodySmall) }
								else CitedSummaryText(s.text, s.citations, onSeekTo = { ms -> onSeekTo?.invoke(ms); onDismiss() })
							}
							if (s.provenance.citations == true && s.status == RunStatus.SUCCESS) Text(
								if (s.citations.isEmpty()) UiStrings.get(StringsRes.string.feature_player_no_verifiable_citations_citations_require_a_segmented, "無可驗證的引用（只支援雲端分段逐字稿）") else UiStrings.get(StringsRes.string.feature_player_timestamps_indicate_matching_words_listen_to_the, "時間標籤只代表該段有出現相同的詞，不保證意思完全出自該段。"),
								style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								OutlinedButton({ regenerateFor = s.sourceTranscriptId }) { Text(stringResource(StringsRes.string.feature_player_generate_in_another_format_alt)) }
								TextButton({ confirmDelete = "s:" + s.id }) { Text(stringResource(StringsRes.string.feature_player_delete_summary)) }
							}
						}
					}
				}
			}
		}
	}

	if (confirmUpload) AlertDialog(
		onDismissRequest = { confirmUpload = false },
		title = { Text(stringResource(StringsRes.string.feature_player_upload_recording_for_transcription)) },
		text = { Text(stringResource(StringsRes.string.feature_player_the_recording_will_be_converted_to_mp3)) },
		confirmButton = { TextButton({ confirmUpload = false; viewModel.start() }) { Text(stringResource(StringsRes.string.feature_player_confirm_upload)) } },
		dismissButton = { TextButton({ confirmUpload = false }) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
	)
	regenerateFor?.let { transcriptId ->
		val stored = state.summaries.firstOrNull { it.sourceTranscriptId == transcriptId }
		var picked by remember(transcriptId) {
			mutableStateOf(SummaryFormat.entries.firstOrNull { it.name != stored?.provenance?.summaryFormat } ?: SummaryFormat.CONCISE)
		}
		AlertDialog(
			onDismissRequest = { regenerateFor = null },
			title = { Text(stringResource(StringsRes.string.feature_player_generate_in_another_format)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(stringResource(StringsRes.string.feature_player_send_the_transcript_again_to_the_saved))
					Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
						SummaryFormat.entries.forEach { f ->
							FilterChip(picked == f, { picked = f }, { Text(f.zh()) })
						}
					}
				}
			},
			confirmButton = {
				TextButton({
					val format = picked
					regenerateFor = null
					viewModel.regenerateSummary(transcriptId, format)
				}) { Text(stringResource(StringsRes.string.feature_player_generate_and_save_a_copy)) }
			},
			dismissButton = { TextButton({ regenerateFor = null }) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
		)
	}
	confirmDelete?.let { target ->
		AlertDialog(
			onDismissRequest = { confirmDelete = null },
			title = { Text(stringResource(StringsRes.string.feature_player_delete_this_result)) },
			text = { Text(stringResource(StringsRes.string.feature_player_only_this_transcript_or_summary_will_be)) },
			confirmButton = { TextButton({
				if (target.startsWith("t:")) viewModel.deleteTranscript(target.drop(2)) else viewModel.deleteSummary(target.drop(2))
				compare = compare - target.drop(2); confirmDelete = null
			}) { Text(stringResource(StringsRes.string.feature_player_delete)) } },
			dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
		)
	}
}
