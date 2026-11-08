package com.eva.feature_settings.composables.cloudai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.live.LiveCloudSettingsRepository
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.secure.SummaryApiKeyStore
import com.eva.feature_settings.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.eva.cloudtranscribe.api.ProbeTransport
import com.eva.cloudtranscribe.live.SummaryProbe

@HiltViewModel
internal class LiveCloudSettingsViewModel @Inject constructor(
	private val repo: LiveCloudSettingsRepository,
	private val summaryKeys: SummaryApiKeyStore,
	private val asrKeys: ApiKeyStore,
) : ViewModel() {
	var testing by mutableStateOf(false); private set
	var testResult by mutableStateOf<String?>(null); private set
	private var testJob: Job? = null
	private var probeTransport: ProbeTransport? = null

	/** Single in-flight, bounded, cancellable test of the SAVED summary settings. */
	fun testSaved() {
		if (testing) return
		testing = true; testResult = null
		testJob = viewModelScope.launch {
			val transport = ProbeTransport(); probeTransport = transport
			var timedOut = false
			val watchdog = launch { delay(25_000); timedOut = true; transport.cancel() }
			try {
				val s = repo.current()
				val r = withContext(Dispatchers.IO) { SummaryProbe.run(s, summaryKeys.read(), if (s.summaryReuseTranscribeKey) asrKeys.read() else null, transport) }
				testResult = if (timedOut) "整體測試逾時（25 秒），已停止；未重試。" else r
			} catch (e: CancellationException) {
				testResult = "已取消測試；已送出的請求可能仍計費。"; throw e
			} catch (e: Exception) {
				testResult = "無法讀取已儲存的摘要設定／Key，請檢查後再試。"
			} finally { watchdog.cancel(); transport.cancel(); probeTransport = null; testing = false }
		}
	}
	fun cancelTest() { probeTransport?.cancel(); testJob?.cancel() }
	override fun onCleared() { probeTransport?.cancel(); super.onCleared() }

	val settings: StateFlow<LiveCloudSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
	val hasSummaryKey: StateFlow<Boolean> = summaryKeys.hasKey
	val hasAsrKey: StateFlow<Boolean> = asrKeys.hasKey
	fun save(s: LiveCloudSettings) = viewModelScope.launch { repo.update { s } }
	fun saveKey(k: String) { if (k.isNotBlank()) summaryKeys.save(k) }
	fun removeKey() = summaryKeys.clear()
}

/** Advanced live cloud transcription + key-point summary (default OFF, explicit opt-in). */
@Composable
internal fun LiveCloudSettingsSection(viewModel: LiveCloudSettingsViewModel = hiltViewModel()) {
	val saved by viewModel.settings.collectAsStateWithLifecycle()
	val hasSummaryKey by viewModel.hasSummaryKey.collectAsStateWithLifecycle()
	val hasAsrKey by viewModel.hasAsrKey.collectAsStateWithLifecycle()
	val current = saved ?: return
	var draft by remember(current) { mutableStateOf(current) }
	var keyInput by remember { mutableStateOf("") }
	var confirmEnable by remember { mutableStateOf(false) }
	var confirmReuse by remember { mutableStateOf(false) }
	var msg by remember { mutableStateOf<String?>(null) }
	var confirmTest by remember { mutableStateOf(false) }

	Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
		HorizontalDivider()
		Text("進階：錄音中即時雲端轉錄＋重點整理", style = MaterialTheme.typography.titleMedium)
		Text("預設關閉。開啟後，錄音期間會定期把新錄到的音訊上傳到上方的轉錄服務，並把文字送到下方的摘要服務整理重點；會持續產生流量與 API 費用。原始錄音、離線字幕不受影響。",
			style = MaterialTheme.typography.bodySmall)
		Row2("啟用進階即時雲端模式", draft.enabled) { on ->
			if (on) confirmEnable = true else draft = draft.copy(enabled = false)
		}
		NumberField("雲端轉錄間隔（秒，30–600，預設 60）", draft.transcribeIntervalSec) { draft = draft.copy(transcribeIntervalSec = it) }
		NumberField("重點整理間隔（秒，15–600，預設 30）", draft.summaryIntervalSec) { draft = draft.copy(summaryIntervalSec = it) }
		NumberField("首次整理不早於（秒，預設 90）", draft.firstSummaryDelaySec) { draft = draft.copy(firstSummaryDelaySec = it) }
		Row2("首次整理需先完成一段雲端轉錄", draft.requireCloudBeforeFirstSummary) { draft = draft.copy(requireCloudBeforeFirstSummary = it) }
		Row2("用最新離線字幕暫時補上（雲端稿回來後取代）", draft.offlineSupplement) { draft = draft.copy(offlineSupplement = it) }

		Text("重點整理服務（獨立設定，OpenAI 相容）", style = MaterialTheme.typography.titleSmall)
		OutlinedTextField(draft.summaryBaseUrl, { draft = draft.copy(summaryBaseUrl = it.trim()) }, Modifier.fillMaxWidth(),
			label = { Text("摘要 Endpoint（RONE，不含 /chat/completions）") }, singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
			isError = draft.summaryBaseUrl.isNotBlank() && !draft.summaryBaseUrl.startsWith("https://"))
		OutlinedTextField(draft.summaryModel, { draft = draft.copy(summaryModel = it.trim()) }, Modifier.fillMaxWidth(),
			label = { Text("摘要模型（預設 ${LiveCloudSettings.DEFAULT_SUMMARY_MODEL}）") }, singleLine = true)
		OutlinedTextField(draft.summaryPrompt, { draft = draft.copy(summaryPrompt = it) }, Modifier.fillMaxWidth(),
			label = { Text("重點整理 Prompt（可自行修改）") }, minLines = 4, maxLines = 10)
		TextButton(onClick = { draft = draft.copy(summaryPrompt = LiveCloudSettings.DEFAULT_SUMMARY_PROMPT) }) { Text("還原預設 Prompt") }
		MeetingNotesField(draft.meetingNotes) { draft = draft.copy(meetingNotes = it) }

		Row2("摘要沿用轉錄 API Key（需明確選擇）", draft.summaryReuseTranscribeKey) { on ->
			if (on) confirmReuse = true else draft = draft.copy(summaryReuseTranscribeKey = false)
		}
		if (!draft.summaryReuseTranscribeKey) {
			Text(if (hasSummaryKey) "摘要 Key：已設定（Android Keystore 加密，獨立於轉錄 Key）" else "摘要 Key：尚未設定", style = MaterialTheme.typography.bodySmall)
			OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), label = { Text(if (hasSummaryKey) "輸入新摘要 Key 以取代" else "貼上摘要 API Key") },
				singleLine = true, visualTransformation = PasswordVisualTransformation(),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(enabled = keyInput.isNotBlank(), onClick = { viewModel.saveKey(keyInput); keyInput = "" }) { Text("儲存摘要 Key") }
				OutlinedButton(enabled = hasSummaryKey, onClick = { viewModel.removeKey() }) { Text("移除") }
			}
		}
		Button(onClick = { viewModel.save(draft.normalized()); msg = "已儲存（下次開始錄音時生效）" }, modifier = Modifier.fillMaxWidth()) { Text("儲存進階設定") }
		msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
		val dirty = draft.normalized() != current.normalized() || keyInput.isNotBlank()
		if (dirty) Text("測試只使用已儲存的摘要設定（含 Prompt、Key 選擇）；請先儲存。", style = MaterialTheme.typography.bodySmall)
		Text("測試會用內建短範例文字送出一次摘要請求（可能產生少量費用），不上傳錄音、不自動重試；進階模式未開啟也可測試。", style = MaterialTheme.typography.bodySmall)
		Button(enabled = !dirty && !viewModel.testing, onClick = { confirmTest = true }) {
			Text(if (viewModel.testing) "摘要測試中…" else "測試摘要設定")
		}
		if (viewModel.testing) TextButton(onClick = { viewModel.cancelTest() }) { Text("取消測試") }
		viewModel.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
		if (draft.enabled) draft.validateSummary(hasSummaryKey, hasAsrKey)?.let {
			Text("重點整理尚未可用：$it（雲端轉錄仍會照常進行）", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
		}
	}

	if (confirmEnable) AlertDialog(
		onDismissRequest = { confirmEnable = false },
		title = { Text("開啟進階即時雲端模式？") },
		text = {
			Text("錄音期間每 ${draft.transcribeIntervalSec} 秒會把新錄到的音訊上傳到你設定的轉錄服務，並每 ${draft.summaryIntervalSec} 秒把新增文字送到摘要服務（${draft.summaryModel}）。" +
				"會持續使用行動數據並產生 API 費用；服務慢或失敗時不會影響錄音存檔。")
		},
		confirmButton = {
			TextButton(onClick = {
				draft = draft.copy(enabled = true, consentAcceptedAtMillis = System.currentTimeMillis()); confirmEnable = false
			}) { Text("我了解費用與上傳，開啟") }
		},
		dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } },
	)
	if (confirmTest) AlertDialog(
		onDismissRequest = { confirmTest = false },
		title = { Text("測試已儲存的摘要設定？") },
		text = { Text("會用已儲存的摘要 Endpoint、模型（${current.summaryModel}）、Prompt（含會前備註，若有）與${if (current.summaryReuseTranscribeKey) "沿用的轉錄 Key" else "摘要 Key"}，送出一次短範例文字的摘要請求（上限約 ${SummaryProbe.TOKEN_LIMIT} tokens）。可能產生少量費用；不上傳錄音，不自動重試。") },
		confirmButton = { TextButton(onClick = { confirmTest = false; viewModel.testSaved() }) { Text("同意費用，測試一次") } },
		dismissButton = { TextButton(onClick = { confirmTest = false }) { Text("取消") } },
	)
	if (confirmReuse) AlertDialog(
		onDismissRequest = { confirmReuse = false },
		title = { Text("摘要沿用轉錄 Key？") },
		text = { Text("重點整理會使用轉錄服務的 API Key 呼叫摘要 Endpoint（${draft.summaryBaseUrl.ifBlank { "尚未設定" }}）。只有在兩者是同一服務時才建議這麼做。") },
		confirmButton = { TextButton(onClick = { draft = draft.copy(summaryReuseTranscribeKey = true); confirmReuse = false }) { Text("確定沿用") } },
		dismissButton = { TextButton(onClick = { confirmReuse = false }) { Text("取消") } },
	)
}

/**
 * Optional pre-meeting notes / agenda. The field caps itself at [LiveCloudSettings.MAX_MEETING_NOTES] and clears
 * through its own button so the caller only has to keep the string; the player-facing prompt is built elsewhere and
 * only reads the saved value.
 */
@Composable
internal fun MeetingNotesField(notes: String, onNotesChange: (String) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		OutlinedTextField(
			value = notes,
			onValueChange = { onNotesChange(it.take(LiveCloudSettings.MAX_MEETING_NOTES)) },
			modifier = Modifier.fillMaxWidth(),
			label = { Text(stringResource(R.string.live_cloud_meeting_notes_label)) },
			minLines = 2,
			maxLines = 6,
			supportingText = { Text(stringResource(R.string.live_cloud_meeting_notes_hint)) },
		)
		if (notes.isNotBlank()) TextButton(onClick = { onNotesChange("") }) {
			Text(stringResource(R.string.live_cloud_meeting_notes_clear))
		}
	}
}

@Composable
private fun Row2(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
		Text(label, Modifier.weight(1f))
		Switch(checked, onChange)
	}
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
	var text by remember(value) { mutableStateOf(value.toString()) }
	OutlinedTextField(text, { v -> text = v.filter(Char::isDigit); text.toIntOrNull()?.let(onChange) }, Modifier.fillMaxWidth(),
		label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
