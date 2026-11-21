package com.eva.feature_recordings.rename

import com.eva.feature_recordings.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.cloudtranscribe.title.TitleSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
internal class TitleSuggestionViewModel @Inject constructor(
	private val store: TranscriptStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) : ViewModel() {
	var summary by mutableStateOf<String?>(null)
		private set
	var cloudReady by mutableStateOf(false)
		private set
	var busy by mutableStateOf(false)
		private set
	var message by mutableStateOf<String?>(null)
		private set
	private val _suggestions = MutableSharedFlow<String>(extraBufferCapacity = 1)
	val suggestions = _suggestions.asSharedFlow()
	private var boundId = -1L
	private var job: Job? = null

	fun bind(recordingId: Long) {
		if (boundId == recordingId) return
		boundId = recordingId
		viewModelScope.launch {
			summary = withContext(Dispatchers.IO) {
				runCatching { TitleSuggestion.latestUsable(store.summaries(recordingId))?.text }.getOrNull()
			}
			val s = settingsRepo.current()
			cloudReady = s.baseUrl.isNotBlank() && s.summaryModel.isNotBlank() && withContext(Dispatchers.IO) { !keys.read().isNullOrBlank() }
		}
	}

	fun local() {
		val t = summary?.let(TitleSuggestion::fromSummary)
		if (t == null) message = UiStrings.get(StringsRes.string.feature_recordings_no_usable_title_in_the_summary, "摘要裡沒有可用的標題") else { message = null; _suggestions.tryEmit(t) }
	}

	/** One explicit request with the summary text only; nothing is renamed here. */
	fun ai() {
		val text = summary ?: return
		job?.cancel()
		job = viewModelScope.launch {
			busy = true
			message = null
			try {
				val s = settingsRepo.current()
				val key = withContext(Dispatchers.IO) { keys.read() }
				if (s.baseUrl.isBlank() || key.isNullOrBlank()) { message = UiStrings.get(StringsRes.string.feature_recordings_set_a_cloud_summary_service_and_api, "尚未設定雲端摘要服務或 API Key"); return@launch }
				val client = CloudAiClients.create(s, key, UrlConnectionTransport(), s.summaryModel)
				when (val o = withContext(Dispatchers.IO) { client.complete(TitleSuggestion.prompt(text)) }) {
					is CallOutcome.Text -> TitleSuggestion.parseAi(o.text)?.let { _suggestions.tryEmit(it) } ?: run { message = UiStrings.get(StringsRes.string.feature_recordings_the_response_was_not_a_valid_title, "回應不是有效標題") }
					is CallOutcome.Failure -> message = UiStrings.get(StringsRes.string.feature_recordings_suggestion_failed_alt, "建議失敗：%1\$s", o.message)
					else -> message = UiStrings.get(StringsRes.string.feature_recordings_suggestion_failed, "建議失敗")
				}
			} finally {
				busy = false
			}
		}
	}

	override fun onCleared() { job?.cancel() }
}

/** Buttons under the rename field; a suggestion only fills the field. */
@Composable
internal fun TitleSuggestionRow(recordingId: Long, onSuggest: (String) -> Unit, viewModel: TitleSuggestionViewModel = hiltViewModel()) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	LaunchedEffect(viewModel) { viewModel.suggestions.collect(onSuggest) }
	var confirm by remember { mutableStateOf(false) }
	val hasSummary = viewModel.summary != null
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			OutlinedButton(onClick = viewModel::local, enabled = hasSummary) { Text(stringResource(StringsRes.string.feature_recordings_use_title_from_summary)) }
			OutlinedButton(onClick = { confirm = true }, enabled = hasSummary && viewModel.cloudReady && !viewModel.busy) {
				Text(if (viewModel.busy) UiStrings.get(StringsRes.string.feature_recordings_suggesting, "建議中…") else UiStrings.get(StringsRes.string.feature_recordings_suggest_with_ai, "AI 建議"))
			}
		}
		val hint = viewModel.message ?: when {
			!hasSummary -> UiStrings.get(StringsRes.string.feature_recordings_generate_a_summary_before_requesting_a_title, "有成功的摘要後才能建議標題")
			!viewModel.cloudReady -> UiStrings.get(StringsRes.string.feature_recordings_set_up_a_cloud_summary_service_and, "AI 建議需要先設定雲端摘要服務與 API Key")
			else -> null
		}
		hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
	}
	if (confirm) AlertDialog(
		onDismissRequest = { confirm = false },
		title = { Text(stringResource(StringsRes.string.feature_recordings_suggest_a_title_with_ai)) },
		text = { Text(stringResource(StringsRes.string.feature_recordings_sends_the_latest_summary_up_to_characters, (TitleSuggestion.MAX_SUMMARY_CHARS).toString())) },
		confirmButton = { TextButton({ confirm = false; viewModel.ai() }) { Text(stringResource(StringsRes.string.feature_recordings_send)) } },
		dismissButton = { TextButton({ confirm = false }) { Text(stringResource(StringsRes.string.feature_recordings_cancel)) } },
	)
}
