package com.eva.feature_settings.composables.cloudai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.res.stringResource
import com.eva.feature_settings.R
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.CustomVocabulary
import com.eva.cloudtranscribe.model.VocabularyKind
import com.eva.cloudtranscribe.model.ProviderType
import com.eva.cloudtranscribe.model.ReasoningEffort
import com.eva.cloudtranscribe.model.SummaryFormat
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.feature_settings.composables.drive.DriveSettingsContent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import com.eva.cloudtranscribe.api.ConnectionProbe
import com.eva.cloudtranscribe.api.ProbeTransport

@HiltViewModel
internal class CloudAiSettingsViewModel @Inject constructor(
	private val repo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) : ViewModel() {
	val settings: StateFlow<CloudSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
	val hasKey: StateFlow<Boolean> = keys.hasKey
	var savedKey by mutableStateOf(keys.read())
		private set

	var testing by mutableStateOf(false)
		private set
	var testResult by mutableStateOf<String?>(null)
		private set
	private var testJob: Job? = null
	private var probeTransport: ProbeTransport? = null

	fun testSavedSettings() {
		if (testing) return
		testing = true
		testResult = null
		testJob = viewModelScope.launch {
			val transport = ProbeTransport()
			probeTransport = transport
			var timedOut = false
			val watchdog = launch { delay(20_000); timedOut = true; transport.cancel() }
			try {
				val s = repo.settings.first()
				val result = withContext(Dispatchers.IO) { ConnectionProbe.run(s, keys.read(), transport) }
				testResult = if (timedOut) "整體測試逾時（20 秒），已停止；未重試。" else result
			} catch (e: CancellationException) {
				testResult = "已取消測試；已送出的 API 請求可能仍計費。"
				throw e
			} catch (e: Exception) {
				testResult = "無法讀取已儲存設定／Key，請檢查後再試。"
			} finally {
				watchdog.cancel()
				transport.cancel()
				probeTransport = null
				testing = false
			}
		}
	}
	fun cancelTest() { probeTransport?.cancel(); testJob?.cancel() }
	override fun onCleared() { probeTransport?.cancel(); super.onCleared() }

	fun save(s: CloudSettings) = viewModelScope.launch { repo.update { s } }
	fun saveKey(key: String) {
		if (key.isBlank()) return
		keys.save(key)
		savedKey = keys.read()
	}
	fun removeKey() { keys.clear(); savedKey = null }
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
	var confirmTest by remember { mutableStateOf(false) }
	var confirmAuto by remember { mutableStateOf(false) }
	var confirmAutoSummary by remember { mutableStateOf(false) }
	var savedMsg by remember { mutableStateOf<String?>(null) }
	var peopleText by remember(current) { mutableStateOf(CustomVocabulary.format(current.customVocabulary, VocabularyKind.PERSON)) }
	var termsText by remember(current) { mutableStateOf(CustomVocabulary.format(current.customVocabulary, VocabularyKind.OTHER)) }
	val vocabulary = CustomVocabulary.normalize(CustomVocabulary.parse(peopleText, VocabularyKind.PERSON) + CustomVocabulary.parse(termsText, VocabularyKind.OTHER))

	val dirtyDraft = draft != current || vocabulary != current.customVocabulary
	val saveDraft = {
		viewModel.save(draft.copy(chunkSeconds = draft.normalizedChunkSeconds, workers = draft.normalizedWorkers, customVocabulary = vocabulary))
		savedMsg = "已儲存"
	}
	Box(modifier.fillMaxSize()) {
	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = if (dirtyDraft) 72.dp else 0.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		InfoLine(
			"只有按下按鈕或開啟自動功能時才會上傳到你設定的服務。",
			"雲端轉錄與摘要只在你按下按鈕（或開啟自動轉錄）時，才會把錄音上傳到下方設定的服務。行動網路可能產生流量費用，服務本身也可能計費。",
		)
		RecordingStartDefaultsSection()
		SettingsCard("連線", if (hasKey) current.transcribeModel.ifBlank { "尚未設定模型" } else "尚未設定 API Key", "ai-conn") {
			Text("介面格式", style = MaterialTheme.typography.bodyLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
				FilterChip(draft.providerType == ProviderType.OPENAI_COMPATIBLE, { draft = draft.copy(providerType = ProviderType.OPENAI_COMPATIBLE) }, { Text("OpenAI 相容服務") })
				FilterChip(draft.providerType == ProviderType.GEMINI_NATIVE, { draft = draft.copy(providerType = ProviderType.GEMINI_NATIVE) }, { Text("Gemini 原生") })
			}
			OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it.trim()) }, Modifier.fillMaxWidth(),
				label = { Text("API Endpoint") },
				supportingText = { Text(if (draft.providerType == ProviderType.GEMINI_NATIVE) "如 https://generativelanguage.googleapis.com/v1beta" else "不含 /chat/completions") },
				singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
				isError = draft.baseUrl.isNotBlank() && !draft.baseUrl.startsWith("https://"))
			OutlinedTextField(draft.transcribeModel, { draft = draft.copy(transcribeModel = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("轉錄模型") }, singleLine = true)
			OutlinedTextField(draft.summaryModel, { draft = draft.copy(summaryModel = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("摘要模型") }, singleLine = true)
			Text("API Key", style = MaterialTheme.typography.bodyLarge)
			Text(stringResource(if (hasKey) R.string.cloud_api_key_saved else R.string.cloud_api_key_missing), style = MaterialTheme.typography.bodySmall)
			viewModel.savedKey?.let { key ->
				SelectionContainer {
					OutlinedTextField(key, {}, Modifier.fillMaxWidth().testTag("saved-api-key"),
						readOnly = true, label = { Text(stringResource(R.string.cloud_api_key_saved_label)) })
				}
			}
			OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "輸入新 Key 以取代" else "貼上 API Key") },
				singleLine = true, visualTransformation = PasswordVisualTransformation(),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(enabled = keyInput.isNotBlank(), onClick = { viewModel.saveKey(keyInput); keyInput = "" }) { Text("儲存 Key") }
				OutlinedButton(enabled = hasKey, onClick = { viewModel.removeKey() }) { Text("移除 Key") }
			}
			val dirty = dirtyDraft || keyInput.isNotBlank()
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
				OutlinedButton(
					enabled = !dirty && !viewModel.testing && hasKey && current.baseUrl.isNotBlank() && current.transcribeModel.isNotBlank(),
					onClick = { confirmTest = true },
				) { Text(if (viewModel.testing) "連線測試中…" else "測試連線") }
				if (viewModel.testing) TextButton(onClick = { viewModel.cancelTest() }) { Text("取消測試") }
			}
			InfoLine(
				if (dirty) "測試只用已儲存設定，請先儲存。" else "只送一次極短文字，不上傳錄音。",
				"按測試只會送出一次極短文字（可能產生少量 API 費用），不會上傳錄音，也不會自動重試。僅文字連線測試，不代表支援音訊。",
			)
			viewModel.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
			draft.validate(hasKey)?.let { Text("尚未可用：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
		}
		SettingsCard("摘要", summaryFormatLabel(current.summaryFormat) + "・自動摘要" + onOff(current.autoSummaryAfterTranscript), "ai-summary") {
			Text("摘要格式", style = MaterialTheme.typography.bodyLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
				SummaryFormat.entries.forEach { format ->
					FilterChip(selected = draft.summaryFormat == format, onClick = { draft = draft.copy(summaryFormat = format) }, label = { Text(summaryFormatLabel(format)) })
				}
			}
			Text("只影響之後產生的摘要", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			if (draft.summaryFormat == SummaryFormat.CUSTOM) {
				OutlinedTextField(
					draft.customSummaryInstructions,
					{ draft = draft.copy(customSummaryInstructions = it.take(CloudSettings.MAX_SUMMARY_INSTRUCTIONS)) },
					Modifier.fillMaxWidth(),
					label = { Text("自訂格式說明（空白時退回精簡）") },
					minLines = 3, maxLines = 8,
					supportingText = { Text("只描述排版與取捨；沒說的不寫。") },
				)
			}
			SettingSwitch("附來源時間", draft.summaryCitations, "只限雲端逐字稿；會多用 token") { draft = draft.copy(summaryCitations = it) }
			SettingSwitch("轉錄後自動摘要", draft.autoSummaryAfterTranscript, "每次多一次摘要請求") { if (it) confirmAutoSummary = true else draft = draft.copy(autoSummaryAfterTranscript = false) }
		}
		SettingsCard("轉錄", "自動轉錄" + onOff(current.autoAfterRecording) + "・詞彙 ${current.customVocabulary.size} 個", "ai-transcribe") {
			CustomVocabularySection(peopleText = peopleText, termsText = termsText, kept = vocabulary.size, onPeople = { peopleText = it }, onTerms = { termsText = it })
			SettingSwitch("錄音後自動雲端轉錄", draft.autoAfterRecording) { if (it) confirmAuto = true else draft = draft.copy(autoAfterRecording = false) }
			if (draft.autoAfterRecording) SettingSwitch("只在 Wi‑Fi", draft.autoWifiOnly, "或不計量網路") { draft = draft.copy(autoWifiOnly = it) }
			SettingSwitch("完成時通知", draft.notifyWhenDone, "鎖定畫面不顯示內容") { draft = draft.copy(notifyWhenDone = it) }
		}
		LiveCloudSettingsSection()
		SlackSyncSettingsSection()
		SettingsCard("隱私", "遮個資" + onOff(current.redactPiiForCloud), "ai-privacy") {
			SettingSwitch("送出前遮蔽個資", draft.redactPiiForCloud, "電話、Email、身分證號、卡號；摘要／章節") { draft = draft.copy(redactPiiForCloud = it) }
			if (draft.redactPiiForCloud) InfoLine("只替換送出的文字，原稿不變。", "只在本機替換送出的文字，原始逐字稿不變；雲端音訊轉錄無法遮蔽。格式比對無法保證找出所有個資，人名不遮蔽。")
		}
		SettingsCard("進階", "分段 ${current.normalizedChunkSeconds} 秒・同時 ${current.normalizedWorkers} 段", "ai-advanced", initiallyExpanded = false) {
			Text("reasoning_effort", style = MaterialTheme.typography.bodyLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
				ReasoningEffort.entries.forEach { e -> FilterChip(draft.reasoningEffort == e, { draft = draft.copy(reasoningEffort = e) }, { Text(e.wire ?: "off") }) }
			}
			Text("名稱含 transcribe / flash-lite 的模型一律不送", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			OutlinedTextField(draft.chunkSeconds.toString(), { v -> v.toIntOrNull()?.let { draft = draft.copy(chunkSeconds = it) } }, Modifier.fillMaxWidth(),
				label = { Text("分段秒數") }, supportingText = { Text("60–900，預設 300") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
			OutlinedTextField(draft.workers.toString(), { v -> v.toIntOrNull()?.let { draft = draft.copy(workers = it) } }, Modifier.fillMaxWidth(),
				label = { Text("同時上傳段數") }, supportingText = { Text("1–6，預設 2") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
			SettingSwitch("略過近乎無聲的段落", draft.skipSilentChunks, "小聲說話可能被誤判") { draft = draft.copy(skipSilentChunks = it) }
			SettingSwitch("幻覺長度警示", draft.hallucinationWarning) { draft = draft.copy(hallucinationWarning = it) }
		}
		SettingsCard("用量", "本機紀錄估算", "ai-usage", initiallyExpanded = false) { CloudUsageSection() }
		savedMsg?.takeIf { !dirtyDraft }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
	}
	if (dirtyDraft) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
		Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
			Text("有未儲存的變更", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
			TextButton({ draft = current; peopleText = CustomVocabulary.format(current.customVocabulary, VocabularyKind.PERSON); termsText = CustomVocabulary.format(current.customVocabulary, VocabularyKind.OTHER) }) { Text("捨棄") }
			Button(onClick = saveDraft) { Text("儲存") }
		}
	}
	}

	if (confirmTest) AlertDialog(
		onDismissRequest = { confirmTest = false },
		title = { Text("測試已儲存設定？") },
		text = { Text("會使用已儲存的供應商、Endpoint、API Key 與轉錄模型，送出一次『回覆 OK』的文字請求。可能產生少量 API 費用；沒有任何錄音上傳，不自動重試。") },
		confirmButton = { TextButton(onClick = { confirmTest = false; viewModel.testSavedSettings() }) { Text("同意費用，測試一次") } },
		dismissButton = { TextButton(onClick = { confirmTest = false }) { Text("取消") } },
	)

	if (confirmAutoSummary) AlertDialog(
		onDismissRequest = { confirmAutoSummary = false },
		title = { Text("開啟自動摘要？") },
		text = { Text("每次雲端轉錄成功後（手動或自動轉錄都算），會再送出 1 次摘要請求：只送逐字稿文字、不上傳音訊，使用你設定的摘要模型，可能產生 API 費用。部分完成、失敗或取消的轉錄不會送；同一份逐字稿已有摘要時不會重複送。") },
		confirmButton = { TextButton({ draft = draft.copy(autoSummaryAfterTranscript = true); confirmAutoSummary = false }) { Text("我了解，開啟") } },
		dismissButton = { TextButton({ confirmAutoSummary = false }) { Text("取消") } },
	)

	if (confirmAuto) AlertDialog(
		onDismissRequest = { confirmAuto = false },
		title = { Text("開啟自動雲端轉錄？") },
		text = { Text("每次錄音成功儲存後，會自動把整段錄音上傳到你設定的服務轉錄（取消的錄音不會上傳）。可能產生行動數據與 API 費用；失敗只會重試同一個服務，不會改用其他供應商。") },
		confirmButton = { TextButton({ draft = draft.copy(autoAfterRecording = true); confirmAuto = false }) { Text("我了解，開啟") } },
		dismissButton = { TextButton({ confirmAuto = false }) { Text("取消") } },
	)
}

/** UI label for a summary format; the prompt text lives in Prompts, never here. */
internal fun summaryFormatLabel(format: SummaryFormat): String = when (format) {
	SummaryFormat.CONCISE -> "精簡（預設）"
	SummaryFormat.DECISIONS_AND_ACTIONS -> "決議與待辦"
	SummaryFormat.BULLETS_AND_OPEN_QUESTIONS -> "條列＋未解問題"
	SummaryFormat.CUSTOM -> "自訂"
}

/** Spelling hints for cloud transcription; one term per line. Offline recognition is unaffected. */
@Composable
internal fun CustomVocabularySection(
	peopleText: String,
	termsText: String,
	kept: Int,
	onPeople: (String) -> Unit,
	onTerms: (String) -> Unit,
) {
	Text("自訂詞彙", style = MaterialTheme.typography.bodyLarge)
	InfoLine(
		"每行一個，只當雲端轉錄的拼寫參考。",
		"每行一個，最多 ${CustomVocabulary.MAX_TERMS} 個、每個 ${CustomVocabulary.MAX_LENGTH} 字內。只在雲端轉錄執行時隨音訊一起送到上方設定的服務，" +
			"每次請求的 token 會略增；只當拼寫參考，不會自動取代已存的逐字稿，離線字幕不受影響。錄音中修改的話，下一次錄音才套用。",
	)
	OutlinedTextField(peopleText, onPeople, Modifier.fillMaxWidth().testTag("vocabulary-people"), label = { Text("人名") }, minLines = 2, maxLines = 6)
	OutlinedTextField(termsText, onTerms, Modifier.fillMaxWidth().testTag("vocabulary-terms"), label = { Text("專有名詞、縮寫") }, minLines = 2, maxLines = 6)
	Text("儲存後會保留 $kept 個詞（自動去掉重複、空白與換行符號）", style = MaterialTheme.typography.labelSmall)
}
