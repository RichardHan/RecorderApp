package com.eva.feature_player.cloud

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
		_messages.emit("已排入離線轉錄（${model.displayName}），音訊不會上傳")
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
		_state.value = CloudUiState(store.transcripts(recordingId), store.summaries(recordingId))
	}

	fun start(resume: String? = null) = viewModelScope.launch {
		scheduler.startTranscription(recordingId, resume)
			.onSuccess { _messages.emit("已排入雲端轉錄") }
			.onFailure { _messages.emit(it.message ?: "無法開始") }
		refresh()
	}

	fun cancel(id: String) = scheduler.cancel(id)
	fun summarize(transcriptId: String) = viewModelScope.launch {
		scheduler.startSummary(recordingId, transcriptId).onFailure { _messages.emit(it.message ?: "無法摘要") }.onSuccess { _messages.emit("已排入摘要") }
		refresh()
	}

	/** Another format for the same transcript: always a NEW summary record, the old one is left untouched. */
	fun regenerateSummary(transcriptId: String, format: SummaryFormat) = viewModelScope.launch {
		scheduler.startSummary(recordingId, transcriptId, format)
			.onFailure { _messages.emit(it.message ?: "無法摘要") }
			.onSuccess { _messages.emit("已用「${format.zh()}」排入新摘要") }
		refresh()
	}
	fun deleteTranscript(id: String) = viewModelScope.launch(Dispatchers.IO) { scheduler.cancel(id); store.deleteTranscript(recordingId, id); refresh() }
	fun deleteSummary(id: String) = viewModelScope.launch(Dispatchers.IO) { store.deleteSummary(recordingId, id); refresh() }
}

private val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

private fun TranscriptRecord.label() = "${if (source == TranscriptSource.OFFLINE) (if (provenance.trigger == "live") "離線即時" else "離線") else "雲端"}・${provenance.model}・${fmt.format(Date(createdAtMillis))}"

private fun SummaryFormat.zh() = when (this) {
	SummaryFormat.CONCISE -> "精簡"
	SummaryFormat.DECISIONS_AND_ACTIONS -> "決議與待辦"
	SummaryFormat.BULLETS_AND_OPEN_QUESTIONS -> "條列＋未解問題"
	SummaryFormat.CUSTOM -> "自訂"
}

private fun RunStatus.zh() = when (this) {
	RunStatus.QUEUED -> "排隊中"; RunStatus.RUNNING -> "處理中"; RunStatus.SUCCESS -> "完成"
	RunStatus.PARTIAL -> "部分完成"; RunStatus.FAILED -> "失敗"; RunStatus.CANCELLED -> "已取消"
}

@Composable
fun CloudTranscriptsDialog(
	recordingId: Long,
	onDismiss: () -> Unit,
	viewModel: CloudTranscriptsViewModel = hiltViewModel(),
	/** Seeks playback to a cited segment start; no-op by default. */
	onSeekTo: (Long) -> Unit = {},
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
						Text("逐字稿／雲端重轉／摘要", style = MaterialTheme.typography.titleLarge)
						TextButton(onDismiss) { Text("關閉") }
					}
					Text("原始錄音與離線逐字稿不會被修改；每次雲端重轉都另存一份。", style = MaterialTheme.typography.bodySmall)
					Text("離線重新轉錄（手機運算，不上傳）", style = MaterialTheme.typography.titleSmall)
					if (offlineModels.isEmpty()) Text("尚未下載可用的離線模型（設定 › 轉錄 下載 SenseVoice／Qwen3‑ASR／FireRed）。", style = MaterialTheme.typography.bodySmall)
					else {
						Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
							offlineModels.forEach { m -> FilterChip(offlinePick == m.modelId, { offlinePick = m.modelId }, { Text(m.displayName.substringBefore('（')) }) }
						}
						OutlinedButton(enabled = offlinePick != null, onClick = {
							offlineModels.firstOrNull { it.modelId == offlinePick }?.let(viewModel::startOffline)
						}, modifier = Modifier.fillMaxWidth()) { Text("離線重新轉錄") }
					}
					Button(onClick = { confirmUpload = true }, modifier = Modifier.fillMaxWidth()) { Text("雲端重新轉錄") }
					message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
				}
				if (compare.size == 2) item {
					val a = state.transcripts.firstOrNull { it.id == compare[0] }
					val b = state.transcripts.firstOrNull { it.id == compare[1] }
					if (a != null && b != null) Card {
						Column(Modifier.padding(8.dp)) {
							Text("並排比較", style = MaterialTheme.typography.titleMedium)
							Row(Modifier.fillMaxWidth().heightIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
								listOf(a, b).forEach { t ->
									Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
										Text(t.label(), style = MaterialTheme.typography.labelMedium)
										SelectionContainer { Text(t.text.ifBlank { "（無內容）" }, style = MaterialTheme.typography.bodySmall) }
									}
								}
							}
						}
					}
				}
				if (state.transcripts.isEmpty()) item { Text("尚無逐字稿。") }
				items(state.transcripts, key = { it.id }) { t ->
					Card {
						Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							Text(t.label(), style = MaterialTheme.typography.titleSmall)
							Text("狀態：${t.status.zh()}" + (t.provenance.endpointHost?.let { "・$it" } ?: "") + (t.provenance.reasoningEffort?.let { "・effort=$it" } ?: "") + "・請求 ${t.requestCount} 次・${t.provenance.trigger}", style = MaterialTheme.typography.bodySmall)
							if (t.status == RunStatus.RUNNING || t.status == RunStatus.QUEUED) {
								if (t.totalSegments > 0) LinearProgressIndicator({ t.completedSegments / t.totalSegments.toFloat() }, Modifier.fillMaxWidth())
								else LinearProgressIndicator(Modifier.fillMaxWidth())
							}
							t.error?.let { Text("錯誤：$it", color = MaterialTheme.colorScheme.error) }
							t.warnings.take(5).forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
							if (t.text.isNotBlank()) SelectionContainer { Text(t.text.take(1500) + if (t.text.length > 1500) "…（比較檢視可看全文）" else "", style = MaterialTheme.typography.bodySmall) }
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								FilterChip(t.id in compare, { compare = if (t.id in compare) compare - t.id else (compare + t.id).takeLast(2) }, { Text("比較") })
								if (t.status == RunStatus.RUNNING || t.status == RunStatus.QUEUED) OutlinedButton({ viewModel.cancel(t.id) }) { Text("取消") }
								if (t.source == TranscriptSource.CLOUD && t.status in setOf(RunStatus.PARTIAL, RunStatus.FAILED, RunStatus.CANCELLED)) OutlinedButton({ viewModel.start(t.id) }) { Text("重試未完成段") }
							}
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								if (t.text.isNotBlank() && t.status != RunStatus.RUNNING) OutlinedButton({ viewModel.summarize(t.id) }) { Text("用此稿摘要") }
								TextButton({ confirmDelete = "t:" + t.id }) { Text("刪除") }
							}
						}
					}
				}
				if (state.summaries.isNotEmpty()) item { Text("摘要", style = MaterialTheme.typography.titleMedium) }
				item {
					val newestSummary = state.summaries.firstOrNull { it.status == RunStatus.SUCCESS && it.text.isNotBlank() }
					ActionItemsCard(
						recordingId = recordingId,
						latestSummaryText = newestSummary?.text,
						latestSummaryAtMillis = newestSummary?.createdAtMillis,
					)
				}
				items(state.summaries, key = { it.id }) { s ->
					Card {
						Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
							val src = state.transcripts.firstOrNull { it.id == s.sourceTranscriptId }
							val storedFormat = s.provenance.summaryFormat
								?.let { name -> SummaryFormat.entries.firstOrNull { it.name == name } }
							Text("${s.provenance.model}・${s.status.zh()}・${storedFormat?.let { "${it.zh()}・" } ?: ""}來源：${src?.label() ?: s.sourceTranscriptId}", style = MaterialTheme.typography.labelMedium)
							s.error?.let { Text("錯誤：$it", color = MaterialTheme.colorScheme.error) }
							if (s.text.isNotBlank()) {
								if (s.citations.isEmpty()) SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodySmall) }
								else CitedSummaryText(s.text, s.citations, onSeekTo = { ms -> onSeekTo(ms); onDismiss() })
							}
							if (s.provenance.citations == true && s.status == RunStatus.SUCCESS) Text(
								if (s.citations.isEmpty()) "無可驗證的引用（只支援雲端分段逐字稿）" else "時間標籤只代表該段有出現相同的詞，不保證意思完全出自該段。",
								style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								OutlinedButton({ regenerateFor = s.sourceTranscriptId }) { Text("用其他格式重新整理") }
								TextButton({ confirmDelete = "s:" + s.id }) { Text("刪除摘要") }
							}
						}
					}
				}
			}
		}
	}

	if (confirmUpload) AlertDialog(
		onDismissRequest = { confirmUpload = false },
		title = { Text("上傳錄音到雲端轉錄？") },
		text = { Text("整段錄音會轉成 MP3 分段，上傳到你在「設定 › 雲端 › AI 轉錄／摘要」設定的服務。音訊會離開手機；使用行動網路可能產生流量費，API 也可能計費。失敗只重試同一服務最多 3 次，不會改用其他供應商。") },
		confirmButton = { TextButton({ confirmUpload = false; viewModel.start() }) { Text("確認上傳") } },
		dismissButton = { TextButton({ confirmUpload = false }) { Text("取消") } },
	)
	regenerateFor?.let { transcriptId ->
		val stored = state.summaries.firstOrNull { it.sourceTranscriptId == transcriptId }
		var picked by remember(transcriptId) {
			mutableStateOf(SummaryFormat.entries.firstOrNull { it.name != stored?.provenance?.summaryFormat } ?: SummaryFormat.CONCISE)
		}
		AlertDialog(
			onDismissRequest = { regenerateFor = null },
			title = { Text("用其他格式重新整理？") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text("會用目前儲存的雲端設定（摘要模型與主機）再呼叫一次摘要服務，逐字稿會再次上傳；可能產生費用。選「自訂」但自訂說明是空白時會改用精簡格式。原本的摘要不會被覆蓋，結果會另存一份新摘要。")
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
				}) { Text("呼叫並另存") }
			},
			dismissButton = { TextButton({ regenerateFor = null }) { Text("取消") } },
		)
	}
	confirmDelete?.let { target ->
		AlertDialog(
			onDismissRequest = { confirmDelete = null },
			title = { Text("刪除這份結果？") },
			text = { Text("只刪除這份逐字稿／摘要，不影響原始錄音。") },
			confirmButton = { TextButton({
				if (target.startsWith("t:")) viewModel.deleteTranscript(target.drop(2)) else viewModel.deleteSummary(target.drop(2))
				compare = compare - target.drop(2); confirmDelete = null
			}) { Text("刪除") } },
			dismissButton = { TextButton({ confirmDelete = null }) { Text("取消") } },
		)
	}
}
