package com.eva.feature_settings.composables.cloudai

import com.eva.feature_settings.R as StringsRes
import com.eva.utils.UiStrings

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
import com.eva.cloudtranscribe.model.ReasoningEffortPolicy
import com.eva.cloudtranscribe.model.ReasoningEffortSkip
import com.eva.cloudtranscribe.model.SummaryFormat
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.settings.ModelShortcuts
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
	val recentTranscribeModels: StateFlow<List<String>> =
		repo.recentTranscribeModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
	val recentSummaryModels: StateFlow<List<String>> =
		repo.recentSummaryModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
				testResult = if (timedOut) UiStrings.get(StringsRes.string.feature_settings_test_timed_out_after_20_seconds_no, "整體測試逾時（20 秒），已停止；未重試。") else result
			} catch (e: CancellationException) {
				testResult = UiStrings.get(StringsRes.string.feature_settings_test_canceled_requests_already_sent_may_still, "已取消測試；已送出的 API 請求可能仍計費。")
				throw e
			} catch (e: Exception) {
				testResult = UiStrings.get(StringsRes.string.feature_settings_could_not_read_the_saved_settings_or, "無法讀取已儲存設定／Key，請檢查後再試。")
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

	fun save(s: CloudSettings) = viewModelScope.launch {
		repo.update { s }
		repo.recordRecentTranscribe(s.transcribeModel)
		repo.recordRecentSummary(s.summaryModel)
	}
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
	val recentTranscribe by viewModel.recentTranscribeModels.collectAsStateWithLifecycle()
	val recentSummary by viewModel.recentSummaryModels.collectAsStateWithLifecycle()
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
		savedMsg = UiStrings.get(StringsRes.string.feature_settings_saved, "已儲存")
	}
	Box(modifier.fillMaxSize()) {
	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = if (dirtyDraft) 72.dp else 0.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		InfoLine(
			UiStrings.get(StringsRes.string.feature_settings_uploads_occur_when_you_request_them_or, "只有按下按鈕或開啟自動功能時才會上傳到你設定的服務。"),
			UiStrings.get(StringsRes.string.feature_settings_cloud_transcription_and_summaries_upload_recordings_to, "雲端轉錄與摘要只在你按下按鈕（或開啟自動轉錄）時，才會把錄音上傳到下方設定的服務。行動網路可能產生流量費用，服務本身也可能計費。"),
		)
		RecordingStartDefaultsSection()
		SettingsCard(UiStrings.get(StringsRes.string.feature_settings_connection, "連線"), if (hasKey) current.transcribeModel.ifBlank { UiStrings.get(StringsRes.string.feature_settings_no_model_configured, "尚未設定模型") } else UiStrings.get(StringsRes.string.feature_settings_api_key_missing, "尚未設定 API Key"), "ai-conn") {
			Text(stringResource(StringsRes.string.feature_settings_api_format), style = MaterialTheme.typography.bodyLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
				FilterChip(draft.providerType == ProviderType.OPENAI_COMPATIBLE, { draft = draft.copy(providerType = ProviderType.OPENAI_COMPATIBLE) }, { Text(stringResource(StringsRes.string.feature_settings_openai_compatible_service)) })
				FilterChip(draft.providerType == ProviderType.GEMINI_NATIVE, { draft = draft.copy(providerType = ProviderType.GEMINI_NATIVE, speakerDiarization = false) }, { Text(stringResource(StringsRes.string.feature_settings_native_gemini)) })
			}
			OutlinedTextField(draft.baseUrl, { draft = draft.copy(baseUrl = it.trim()) }, Modifier.fillMaxWidth(),
				label = { Text(UiStrings.get(StringsRes.string.feature_settings_api_endpoint, "API Endpoint")) },
				supportingText = { Text(if (draft.providerType == ProviderType.GEMINI_NATIVE) UiStrings.get(StringsRes.string.feature_settings_for_example_https_generativelanguage_googleapis_com_v1beta, "如 https://generativelanguage.googleapis.com/v1beta") else UiStrings.get(StringsRes.string.feature_settings_exclude_chat_completions, "不含 /chat/completions")) },
				singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
				isError = draft.baseUrl.isNotBlank() && !draft.baseUrl.startsWith("https://"))
			if (draft.providerType == ProviderType.OPENAI_COMPATIBLE) {
				Text(stringResource(R.string.cloud_transcription_mode), style = MaterialTheme.typography.bodyLarge)
				FilterChip(!draft.speakerDiarization && draft.transcribeModel == CloudSettings.DEFAULT_MODEL, {
					draft = draft.copy(transcribeModel = CloudSettings.DEFAULT_MODEL, speakerDiarization = false)
				}, { Text(stringResource(R.string.cloud_mode_argon)) })
				FilterChip(!draft.speakerDiarization && draft.transcribeModel == CloudSettings.FLASH_MODEL, {
					draft = draft.copy(transcribeModel = CloudSettings.FLASH_MODEL, speakerDiarization = false)
				}, { Text(stringResource(R.string.cloud_mode_flash)) })
				FilterChip(draft.speakerDiarization, {
					draft = draft.copy(transcribeModel = CloudSettings.SPEAKER_MODEL, speakerDiarization = true)
				}, { Text(stringResource(R.string.cloud_mode_speakers)) })
				Text(stringResource(R.string.cloud_mode_benchmark), style = MaterialTheme.typography.bodySmall)
				if (draft.speakerDiarization) Text(stringResource(R.string.cloud_mode_speakers_hint), style = MaterialTheme.typography.bodySmall)
			}
			OutlinedTextField(draft.transcribeModel, { draft = draft.copy(transcribeModel = it.trim(), speakerDiarization = false) }, Modifier.fillMaxWidth(), label = { Text(stringResource(StringsRes.string.feature_settings_transcription_model)) }, singleLine = true)
			ModelShortcutChips(
				current = draft.transcribeModel,
				recents = recentTranscribe,
				presets = ModelShortcuts.PRESETS,
				onPick = { draft = draft.copy(transcribeModel = it, speakerDiarization = draft.providerType == ProviderType.OPENAI_COMPATIBLE && it == CloudSettings.SPEAKER_MODEL) },
				testTag = "transcribe-model-shortcuts",
			)
			OutlinedTextField(draft.summaryModel, { draft = draft.copy(summaryModel = it.trim()) }, Modifier.fillMaxWidth(), label = { Text(stringResource(StringsRes.string.feature_settings_summary_model)) }, singleLine = true)
			ModelShortcutChips(
				current = draft.summaryModel,
				recents = recentSummary,
				presets = ModelShortcuts.PRESETS,
				onPick = { draft = draft.copy(summaryModel = it) },
				testTag = "summary-model-shortcuts",
			)
			Text(UiStrings.get(StringsRes.string.feature_settings_api_key, "API Key"), style = MaterialTheme.typography.bodyLarge)
			Text(stringResource(if (hasKey) R.string.cloud_api_key_saved else R.string.cloud_api_key_missing), style = MaterialTheme.typography.bodySmall)
			viewModel.savedKey?.let { key ->
				SelectionContainer {
					OutlinedTextField(key, {}, Modifier.fillMaxWidth().testTag("saved-api-key"),
						readOnly = true, label = { Text(stringResource(R.string.cloud_api_key_saved_label)) })
				}
			}
			OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) UiStrings.get(StringsRes.string.feature_settings_enter_a_replacement_key, "輸入新 Key 以取代") else UiStrings.get(StringsRes.string.feature_settings_paste_api_key, "貼上 API Key")) },
				singleLine = true, visualTransformation = PasswordVisualTransformation(),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(enabled = keyInput.isNotBlank(), onClick = { viewModel.saveKey(keyInput); keyInput = "" }) { Text(stringResource(StringsRes.string.feature_settings_save_key)) }
				OutlinedButton(enabled = hasKey, onClick = { viewModel.removeKey() }) { Text(stringResource(StringsRes.string.feature_settings_remove_key)) }
			}
			val dirty = dirtyDraft || keyInput.isNotBlank()
			if (draft.speakerDiarization) {
				Text(stringResource(R.string.cloud_mode_audio_test), style = MaterialTheme.typography.bodySmall)
			} else {
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
					OutlinedButton(
						enabled = !dirty && !viewModel.testing && hasKey && current.baseUrl.isNotBlank() && current.transcribeModel.isNotBlank(),
						onClick = { confirmTest = true },
					) { Text(if (viewModel.testing) UiStrings.get(StringsRes.string.feature_settings_testing_connection, "連線測試中…") else UiStrings.get(StringsRes.string.feature_settings_test_connection, "測試連線")) }
					if (viewModel.testing) TextButton(onClick = { viewModel.cancelTest() }) { Text(stringResource(StringsRes.string.feature_settings_cancel_test)) }
				}
				InfoLine(
					if (dirty) UiStrings.get(StringsRes.string.feature_settings_save_changes_before_testing_the_test_uses, "測試只用已儲存設定，請先儲存。") else UiStrings.get(StringsRes.string.feature_settings_sends_one_short_text_request_no_audio, "只送一次極短文字，不上傳錄音。"),
					UiStrings.get(StringsRes.string.feature_settings_sends_one_short_text_request_a_small, "按測試只會送出一次極短文字（可能產生少量 API 費用），不會上傳錄音，也不會自動重試。僅文字連線測試，不代表支援音訊。"),
				)
				viewModel.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
			}
			draft.validate(hasKey)?.let { Text(stringResource(StringsRes.string.feature_settings_unavailable, (it).toString()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
		}
		SettingsCard(UiStrings.get(StringsRes.string.feature_settings_summary, "摘要"), summaryFormatLabel(current.summaryFormat) + UiStrings.get(StringsRes.string.feature_settings_auto_summary, "・自動摘要") + onOff(current.autoSummaryAfterTranscript) + stringResource(StringsRes.string.auto_title_short) + onOff(current.autoTitleAfterSummary), "ai-summary") {
			Text(stringResource(StringsRes.string.feature_settings_summary_format), style = MaterialTheme.typography.bodyLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
				SummaryFormat.entries.forEach { format ->
					FilterChip(selected = draft.summaryFormat == format, onClick = { draft = draft.copy(summaryFormat = format) }, label = { Text(summaryFormatLabel(format)) })
				}
			}
			Text(stringResource(StringsRes.string.feature_settings_applies_to_future_summaries), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			if (draft.summaryFormat == SummaryFormat.CUSTOM) {
				OutlinedTextField(
					draft.customSummaryInstructions,
					{ draft = draft.copy(customSummaryInstructions = it.take(CloudSettings.MAX_SUMMARY_INSTRUCTIONS)) },
					Modifier.fillMaxWidth(),
					label = { Text(stringResource(StringsRes.string.feature_settings_custom_format_instructions_blank_uses_concise_format)) },
					minLines = 3, maxLines = 8,
					supportingText = { Text(stringResource(StringsRes.string.feature_settings_describe_the_layout_and_what_to_include)) },
				)
			}
			SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_include_source_timestamps, "附來源時間"), draft.summaryCitations, UiStrings.get(StringsRes.string.feature_settings_for_cloud_transcripts_uses_additional_tokens, "只限雲端逐字稿；會多用 token")) { draft = draft.copy(summaryCitations = it) }
			SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_summarize_after_transcription, "轉錄後自動摘要"), draft.autoSummaryAfterTranscript, androidx.compose.ui.res.stringResource(com.eva.feature_settings.R.string.auto_summary_cost_hint)) { if (it) confirmAutoSummary = true else draft = draft.copy(autoSummaryAfterTranscript = false) }
			SettingSwitch(stringResource(StringsRes.string.auto_title_setting), draft.autoTitleAfterSummary, stringResource(StringsRes.string.auto_title_cost_hint)) { draft = draft.copy(autoTitleAfterSummary = it) }
		}
		SettingsCard(UiStrings.get(StringsRes.string.feature_settings_transcription, "轉錄"), UiStrings.get(StringsRes.string.feature_settings_automatic_transcription, "自動轉錄") + onOff(current.autoAfterRecording) + UiStrings.get(StringsRes.string.feature_settings_vocabulary_terms, "・詞彙 %1\$s 個", current.customVocabulary.size), "ai-transcribe") {
			CustomVocabularySection(peopleText = peopleText, termsText = termsText, kept = vocabulary.size, onPeople = { peopleText = it }, onTerms = { termsText = it })
			SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_automatically_transcribe_saved_recordings_in_the_cloud, "錄音後自動雲端轉錄"), draft.autoAfterRecording) { if (it) confirmAuto = true else draft = draft.copy(autoAfterRecording = false) }
			if (draft.autoAfterRecording) SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_wi_fi_only, "只在 Wi‑Fi"), draft.autoWifiOnly, UiStrings.get(StringsRes.string.feature_settings_or_an_unmetered_network, "或不計量網路")) { draft = draft.copy(autoWifiOnly = it) }
			SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_notify_on_completion, "完成時通知"), draft.notifyWhenDone, UiStrings.get(StringsRes.string.feature_settings_hide_content_on_lock_screen, "鎖定畫面不顯示內容")) { draft = draft.copy(notifyWhenDone = it) }
		}
		LiveCloudSettingsSection()
		SlackSyncSettingsSection()
		SettingsCard(UiStrings.get(StringsRes.string.feature_settings_privacy, "隱私"), UiStrings.get(StringsRes.string.feature_settings_mask_personal_data, "遮個資") + onOff(current.redactPiiForCloud), "ai-privacy") {
			SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_mask_personal_data_before_sending, "送出前遮蔽個資"), draft.redactPiiForCloud, UiStrings.get(StringsRes.string.feature_settings_phone_numbers_emails_id_and_card_numbers, "電話、Email、身分證號、卡號；摘要／章節")) { draft = draft.copy(redactPiiForCloud = it) }
			if (draft.redactPiiForCloud) InfoLine(UiStrings.get(StringsRes.string.feature_settings_mask_outgoing_text_only_keep_the_original, "只替換送出的文字，原稿不變。"), UiStrings.get(StringsRes.string.feature_settings_masks_outgoing_text_locally_the_original_transcript, "只在本機替換送出的文字，原始逐字稿不變；雲端音訊轉錄無法遮蔽。格式比對無法保證找出所有個資，人名不遮蔽。"))
		}
		SettingsCard(UiStrings.get(StringsRes.string.feature_settings_advanced, "錄後轉錄進階"), UiStrings.get(StringsRes.string.feature_settings_second_segments_at_once, "分段 %1\$s 秒・同時 %2\$s 段", current.normalizedChunkSeconds, current.normalizedWorkers), "ai-advanced", initiallyExpanded = false) {
			Text(UiStrings.get(StringsRes.string.feature_settings_reasoning_effort, "reasoning_effort"), style = MaterialTheme.typography.bodyLarge)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
				ReasoningEffort.entries.forEach { e -> FilterChip(draft.reasoningEffort == e, { draft = draft.copy(reasoningEffort = e) }, { Text(e.wire ?: UiStrings.get(StringsRes.string.feature_settings_off, "off")) }) }
			}
			Text(reasoningEffortNote(draft), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			Text(stringResource(StringsRes.string.feature_settings_chunk_settings_post_recording_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			OutlinedTextField(draft.chunkSeconds.toString(), { v -> v.toIntOrNull()?.let { draft = draft.copy(chunkSeconds = it) } }, Modifier.fillMaxWidth(),
				label = { Text(stringResource(StringsRes.string.feature_settings_segment_length_in_seconds)) }, supportingText = { Text(stringResource(StringsRes.string.feature_settings_60_900_default_300)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
			OutlinedTextField(draft.workers.toString(), { v -> v.toIntOrNull()?.let { draft = draft.copy(workers = it) } }, Modifier.fillMaxWidth(),
				label = { Text(stringResource(StringsRes.string.feature_settings_concurrent_uploads)) }, supportingText = { Text(stringResource(StringsRes.string.feature_settings_1_6_default_2)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
			SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_skip_nearly_silent_segments, "略過近乎無聲的段落"), draft.skipSilentChunks, UiStrings.get(StringsRes.string.feature_settings_quiet_speech_may_be_skipped, "小聲說話可能被誤判")) { draft = draft.copy(skipSilentChunks = it) }
			SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_warn_about_unusually_long_transcripts, "幻覺長度警示"), draft.hallucinationWarning) { draft = draft.copy(hallucinationWarning = it) }
		}
		SettingsCard(UiStrings.get(StringsRes.string.feature_settings_usage, "用量"), UiStrings.get(StringsRes.string.feature_settings_estimate_from_local_history, "本機紀錄估算"), "ai-usage", initiallyExpanded = false) { CloudUsageSection(prices = draft.modelPrices, transcribeModel = draft.transcribeModel, summaryModel = draft.summaryModel, onPricesChange = { draft = draft.copy(modelPrices = it) }) }
		savedMsg?.takeIf { !dirtyDraft }?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
	}
	if (dirtyDraft) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
		Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(stringResource(StringsRes.string.feature_settings_unsaved_changes), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
			TextButton({ draft = current; peopleText = CustomVocabulary.format(current.customVocabulary, VocabularyKind.PERSON); termsText = CustomVocabulary.format(current.customVocabulary, VocabularyKind.OTHER) }) { Text(stringResource(StringsRes.string.feature_settings_discard)) }
			Button(onClick = saveDraft) { Text(stringResource(StringsRes.string.feature_settings_save)) }
		}
	}
	}

	if (confirmTest) AlertDialog(
		onDismissRequest = { confirmTest = false },
		title = { Text(stringResource(StringsRes.string.feature_settings_test_saved_settings)) },
		text = { Text(stringResource(StringsRes.string.feature_settings_sends_one_short_text_request_asking_for)) },
		confirmButton = { TextButton(onClick = { confirmTest = false; viewModel.testSavedSettings() }) { Text(stringResource(StringsRes.string.feature_settings_accept_charges_and_test_once)) } },
		dismissButton = { TextButton(onClick = { confirmTest = false }) { Text(stringResource(StringsRes.string.feature_settings_cancel)) } },
	)

	if (confirmAutoSummary) AlertDialog(
		onDismissRequest = { confirmAutoSummary = false },
		title = { Text(stringResource(StringsRes.string.feature_settings_enable_automatic_summaries)) },
		text = { Text(androidx.compose.ui.res.stringResource(com.eva.feature_settings.R.string.auto_summary_cost_details)) },
		confirmButton = { TextButton({ draft = draft.copy(autoSummaryAfterTranscript = true); confirmAutoSummary = false }) { Text(stringResource(StringsRes.string.feature_settings_accept_and_enable)) } },
		dismissButton = { TextButton({ confirmAutoSummary = false }) { Text(stringResource(StringsRes.string.feature_settings_cancel)) } },
	)

	if (confirmAuto) AlertDialog(
		onDismissRequest = { confirmAuto = false },
		title = { Text(stringResource(StringsRes.string.feature_settings_enable_automatic_cloud_transcription)) },
		text = { Text(stringResource(StringsRes.string.feature_settings_each_saved_recording_is_uploaded_to_your)) },
		confirmButton = { TextButton({ draft = draft.copy(autoAfterRecording = true); confirmAuto = false }) { Text(stringResource(StringsRes.string.feature_settings_accept_and_enable)) } },
		dismissButton = { TextButton({ confirmAuto = false }) { Text(stringResource(StringsRes.string.feature_settings_cancel)) } },
	)
}

/** UI label for a summary format; the prompt text lives in Prompts, never here. */
internal fun summaryFormatLabel(format: SummaryFormat): String = when (format) {
	SummaryFormat.CONCISE -> UiStrings.get(StringsRes.string.feature_settings_concise_default, "精簡（預設）")
	SummaryFormat.DECISIONS_AND_ACTIONS -> UiStrings.get(StringsRes.string.feature_settings_decisions_and_action_items, "決議與待辦")
	SummaryFormat.BULLETS_AND_OPEN_QUESTIONS -> UiStrings.get(StringsRes.string.feature_settings_bullets_and_open_questions, "條列＋未解問題")
	SummaryFormat.CUSTOM -> UiStrings.get(StringsRes.string.feature_settings_custom, "自訂")
}

@Composable
private fun reasoningEffortNote(settings: CloudSettings): String {
	val note = ReasoningEffortPolicy.of(settings)
	val summarySkip = note.summarySkip
	val transcribeSkip = note.transcribeSkip
	val summaryClause = if (summarySkip == null) {
		stringResource(StringsRes.string.feature_settings_reasoning_applies_to_summary, note.summaryModel)
	} else {
		stringResource(
			StringsRes.string.feature_settings_reasoning_applies_to_summary_skipped,
			note.summaryModel,
			reasoningSkipText(summarySkip),
		)
	}
	val transcribeClause = if (transcribeSkip == null) {
		stringResource(StringsRes.string.feature_settings_reasoning_transcribe_sent, note.transcribeModel)
	} else {
		stringResource(
			StringsRes.string.feature_settings_reasoning_transcribe_skipped,
			note.transcribeModel,
			reasoningSkipText(transcribeSkip),
		)
	}
	return stringResource(StringsRes.string.feature_settings_reasoning_effort_note, summaryClause, transcribeClause)
}

@Composable
private fun reasoningSkipText(skip: ReasoningEffortSkip): String = stringResource(
	when (skip) {
		ReasoningEffortSkip.OFF -> StringsRes.string.feature_settings_reasoning_skip_off
		ReasoningEffortSkip.DEFAULT_TRANSCRIBE_MODEL -> StringsRes.string.feature_settings_reasoning_skip_default_model
		ReasoningEffortSkip.SPEAKER_DIARIZATION -> StringsRes.string.feature_settings_reasoning_skip_diarization
		ReasoningEffortSkip.MODEL_NAME_TRANSCRIBE -> StringsRes.string.feature_settings_reasoning_skip_transcribe_name
		ReasoningEffortSkip.MODEL_NAME_FLASH_LITE -> StringsRes.string.feature_settings_reasoning_skip_flash_lite
		ReasoningEffortSkip.GEMINI_NATIVE -> StringsRes.string.feature_settings_reasoning_skip_gemini_native
	},
)

/** Spelling hints for cloud transcription; one term per line. Offline recognition is unaffected. */
@Composable
internal fun CustomVocabularySection(
	peopleText: String,
	termsText: String,
	kept: Int,
	onPeople: (String) -> Unit,
	onTerms: (String) -> Unit,
) {
	Text(stringResource(StringsRes.string.feature_settings_custom_vocabulary), style = MaterialTheme.typography.bodyLarge)
	InfoLine(
		UiStrings.get(StringsRes.string.feature_settings_one_term_per_line_as_a_spelling, "每行一個，只當雲端轉錄的拼寫參考。"),
		UiStrings.get(StringsRes.string.feature_settings_one_term_per_line_up_to_terms, "每行一個，最多 %1\$s 個、每個 %2\$s 字內。只在雲端轉錄執行時隨音訊一起送到上方設定的服務，", CustomVocabulary.MAX_TERMS, CustomVocabulary.MAX_LENGTH) +
			UiStrings.get(StringsRes.string.feature_settings_each_request_uses_slightly_more_tokens_terms, "每次請求的 token 會略增；只當拼寫參考，不會自動取代已存的逐字稿，離線字幕不受影響。錄音中修改的話，下一次錄音才套用。"),
	)
	OutlinedTextField(peopleText, onPeople, Modifier.fillMaxWidth().testTag("vocabulary-people"), label = { Text(UiStrings.get(StringsRes.string.feature_settings_names, "人名")) }, minLines = 2, maxLines = 6)
	OutlinedTextField(termsText, onTerms, Modifier.fillMaxWidth().testTag("vocabulary-terms"), label = { Text(UiStrings.get(StringsRes.string.feature_settings_terms_and_abbreviations, "專有名詞、縮寫")) }, minLines = 2, maxLines = 6)
	Text(stringResource(StringsRes.string.feature_settings_terms_will_be_saved_after_removing_duplicates, (kept).toString()), style = MaterialTheme.typography.labelSmall)
}
