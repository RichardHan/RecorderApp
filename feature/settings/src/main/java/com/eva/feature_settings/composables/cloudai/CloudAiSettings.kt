package com.eva.feature_settings.composables.cloudai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.ProviderType
import com.eva.cloudtranscribe.model.ReasoningEffort
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.feature_settings.composables.drive.DriveSettingsContent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
internal class CloudAiSettingsViewModel @Inject constructor(
	private val repo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) : ViewModel() {
	val settings: StateFlow<CloudSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
	val hasKey: StateFlow<Boolean> = keys.hasKey

	fun save(s: CloudSettings) = viewModelScope.launch { repo.update { s } }
	fun saveKey(key: String) { if (key.isNotBlank()) keys.save(key) }
	fun removeKey() = keys.clear()
}

/** Cloud tab: switch between Google Drive backup and AI (cloud transcription / summary) settings. */
@Composable
internal fun CloudTabContent(modifier: Modifier = Modifier) {
	var tab by rememberSaveable { mutableIntStateOf(0) }
	Column(modifier.fillMaxSize()) {
		SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
			listOf("Google Drive", "AI 轉錄／摘要").forEachIndexed { i, label ->
				SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
			}
		}
		if (tab == 0) DriveSettingsContent() else CloudAiSettingsContent()
	}
}

@Composable
internal fun CloudAiSettingsContent(
	modifier: Modifier = Modifier,
	viewModel: CloudAiSettingsViewModel = hiltViewModel(),
) {
	val saved by viewModel.settings.collectAsStateWithLifecycle()
	val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
	val current = saved ?: return
	var draft by remember(current) { mutableStateOf(current) }
	var keyInput by remember { mutableStateOf("") }
	var confirmAuto by remember { mutableStateOf(false) }
	var savedMsg by remember { mutableStateOf<String?>(null) }

	Column(
		modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text("雲端轉錄與摘要只在你按下按鈕（或開啟自動轉錄）時，才會把錄音上傳到下方設定的服務。行動網路可能產生流量費用，服務本身也可能計費。", style = MaterialTheme.typography.bodySmall)
		Text("介面格式", style = MaterialTheme.typography.titleSmall)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			FilterChip(draft.providerType == ProviderType.OPENAI_COMPATIBLE, { draft = draft.copy(providerType = ProviderType.OPENAI_COMPATIBLE) }, { Text("OpenAI 相容（公司 RONE）") })
			FilterChip(draft.providerType == ProviderType.GEMINI_NATIVE, { draft = draft.copy(providerType = ProviderType.GEMINI_NATIVE) }, { Text("Gemini 原生") })
		}
		OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it.trim()) }, Modifier.fillMaxWidth(),
			label = { Text(if (draft.providerType == ProviderType.GEMINI_NATIVE) "API Endpoint（如 https://generativelanguage.googleapis.com/v1beta）" else "API Endpoint（不含 /chat/completions）") },
			singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
			isError = draft.baseUrl.isNotBlank() && !draft.baseUrl.startsWith("https://"))
		OutlinedTextField(draft.transcribeModel, { draft = draft.copy(transcribeModel = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("轉錄模型") }, singleLine = true)
		OutlinedTextField(draft.summaryModel, { draft = draft.copy(summaryModel = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("摘要模型") }, singleLine = true)
		Text("reasoning_effort（名稱含 transcribe / flash-lite 的模型一律不送）", style = MaterialTheme.typography.titleSmall)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			ReasoningEffort.entries.forEach { e -> FilterChip(draft.reasoningEffort == e, { draft = draft.copy(reasoningEffort = e) }, { Text(e.wire ?: "off") }) }
		}
		OutlinedTextField(draft.chunkSeconds.toString(), { v -> v.toIntOrNull()?.let { draft = draft.copy(chunkSeconds = it) } }, Modifier.fillMaxWidth(),
			label = { Text("分段秒數（60–900，預設 300）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
		OutlinedTextField(draft.workers.toString(), { v -> v.toIntOrNull()?.let { draft = draft.copy(workers = it) } }, Modifier.fillMaxWidth(),
			label = { Text("同時上傳段數（1–6，手機預設 2）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
		SwitchRow("幻覺長度警示", draft.hallucinationWarning) { draft = draft.copy(hallucinationWarning = it) }
		SwitchRow("錄音完成後自動雲端轉錄（預設關閉）", draft.autoAfterRecording) { if (it) confirmAuto = true else draft = draft.copy(autoAfterRecording = false) }
		if (draft.autoAfterRecording) SwitchRow("自動轉錄只在 Wi‑Fi／不計量網路", draft.autoWifiOnly) { draft = draft.copy(autoWifiOnly = it) }
		Button(onClick = {
			viewModel.save(draft.copy(chunkSeconds = draft.normalizedChunkSeconds, workers = draft.normalizedWorkers))
			savedMsg = "已儲存"
		}, modifier = Modifier.fillMaxWidth()) { Text("儲存設定") }
		savedMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

		Text("API Key", style = MaterialTheme.typography.titleSmall)
		Text(if (hasKey) "已設定（以 Android Keystore 加密保存，不會備份、不顯示明文）" else "尚未設定", style = MaterialTheme.typography.bodySmall)
		OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "輸入新 Key 以取代" else "貼上 API Key") },
			singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(enabled = keyInput.isNotBlank(), onClick = { viewModel.saveKey(keyInput); keyInput = "" }) { Text("儲存 Key") }
			OutlinedButton(enabled = hasKey, onClick = { viewModel.removeKey() }) { Text("移除 Key") }
		}
		draft.validate(hasKey)?.let { Text("尚未可用：$it", color = MaterialTheme.colorScheme.error) }
	}

	if (confirmAuto) AlertDialog(
		onDismissRequest = { confirmAuto = false },
		title = { Text("開啟自動雲端轉錄？") },
		text = { Text("每次錄音成功儲存後，會自動把整段錄音上傳到你設定的服務轉錄（取消的錄音不會上傳）。可能產生行動數據與 API 費用；失敗只會重試同一個服務，不會改用其他供應商。") },
		confirmButton = { TextButton({ draft = draft.copy(autoAfterRecording = true); confirmAuto = false }) { Text("我了解，開啟") } },
		dismissButton = { TextButton({ confirmAuto = false }) { Text("取消") } },
	)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
		Text(label, Modifier.weight(1f))
		Switch(checked, onChange)
	}
}
