package com.eva.feature_settings.composables.cloudai

import com.eva.feature_settings.R as StringsRes
import com.eva.utils.UiStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.eva.cloudtranscribe.live.LiveCloudConsentCopy
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
				testResult = if (timedOut) UiStrings.get(StringsRes.string.feature_settings_test_timed_out_after_25_seconds_no, "整體測試逾時（25 秒），已停止；未重試。") else r
			} catch (e: CancellationException) {
				testResult = UiStrings.get(StringsRes.string.feature_settings_test_canceled_requests_already_sent_may_still_alt, "已取消測試；已送出的請求可能仍計費。"); throw e
			} catch (e: Exception) {
				testResult = UiStrings.get(StringsRes.string.feature_settings_could_not_read_the_saved_summary_settings, "無法讀取已儲存的摘要設定／Key，請檢查後再試。")
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

	SettingsCard(UiStrings.get(StringsRes.string.feature_settings_live_cloud_mode, "即時雲端模式"), if (current.enabled) UiStrings.get(StringsRes.string.feature_settings_enabled_alt, "已開啟・%1\$s", current.summaryModel.ifBlank { UiStrings.get(StringsRes.string.feature_settings_no_model_configured_alt, "未設定模型") }) else UiStrings.get(StringsRes.string.feature_settings_close, "關閉"), "ai-live") {
		InfoLine(
			UiStrings.get(StringsRes.string.feature_settings_off_by_default_when_enabled_new_audio, "預設關閉。開啟後錄音期間會定期上傳新音訊並整理重點。"),
			UiStrings.get(StringsRes.string.feature_settings_during_recording_new_audio_goes_to_the, "開啟後，錄音期間會定期把新錄到的音訊上傳到上方的轉錄服務，並把文字送到下方的摘要服務整理重點；會持續產生流量與 API 費用。原始錄音、離線字幕不受影響。"),
		)
		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_enable_live_cloud_mode, "啟用即時雲端模式"), draft.enabled) { on ->
			if (on) confirmEnable = true else draft = draft.copy(enabled = false)
		}
		NumberField(UiStrings.get(StringsRes.string.feature_settings_cloud_transcription_interval_in_seconds_15_600, "雲端轉錄間隔（秒，15–600，預設 60）"), draft.transcribeIntervalSec) { draft = draft.copy(transcribeIntervalSec = it) }
		NumberField(UiStrings.get(StringsRes.string.feature_settings_highlight_interval_in_seconds_15_600_default, "重點整理間隔（秒，15–600，預設 30）"), draft.summaryIntervalSec) { draft = draft.copy(summaryIntervalSec = it) }
		NumberField(UiStrings.get(StringsRes.string.feature_settings_earliest_first_summary_in_seconds_default_90, "首次整理不早於（秒，預設 90）"), draft.firstSummaryDelaySec) { draft = draft.copy(firstSummaryDelaySec = it) }
		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_wait_for_one_cloud_transcript_before_the, "首次整理需先完成一段雲端轉錄"), draft.requireCloudBeforeFirstSummary) { draft = draft.copy(requireCloudBeforeFirstSummary = it) }
		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_use_offline_captions_temporarily, "用離線字幕暫時補上"), draft.offlineSupplement, UiStrings.get(StringsRes.string.feature_settings_replace_when_the_cloud_transcript_arrives, "雲端稿回來後取代")) { draft = draft.copy(offlineSupplement = it) }
		SettingSwitch(
			UiStrings.get(StringsRes.string.feature_settings_improve_mixed_chinese_english_transcription_and_punctuation, "提高中英夾雜與斷句品質"),
			draft.enhanceContextQuality,
			UiStrings.get(StringsRes.string.feature_settings_overlap_adjacent_segments_by_about_3_seconds, "相鄰片段短重疊約 3 秒（上傳量約 +10%），並帶入前文與名稱脈絡；文字校正在背景進行，不延遲顯示。會多用一些 token。"),
		) { draft = draft.copy(enhanceContextQuality = it) }
		if (draft.enhanceContextQuality) {
			NumberField(UiStrings.get(StringsRes.string.feature_settings_audio_overlap_in_seconds_0_5_default, "音訊重疊（秒，0–5，預設 3）"), draft.overlapSec) { draft = draft.copy(overlapSec = it) }
		}

		Text(stringResource(StringsRes.string.feature_settings_highlight_service), style = MaterialTheme.typography.bodyLarge)
		OutlinedTextField(draft.summaryBaseUrl, { draft = draft.copy(summaryBaseUrl = it.trim()) }, Modifier.fillMaxWidth(),
			label = { Text(stringResource(StringsRes.string.feature_settings_summary_endpoint_exclude_chat_completions)) }, singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
			isError = draft.summaryBaseUrl.isNotBlank() && !draft.summaryBaseUrl.startsWith("https://"))
		OutlinedTextField(draft.summaryModel, { draft = draft.copy(summaryModel = it.trim()) }, Modifier.fillMaxWidth(),
			label = { Text(stringResource(StringsRes.string.feature_settings_summary_model_default, (LiveCloudSettings.DEFAULT_SUMMARY_MODEL).toString())) }, singleLine = true)
		OutlinedTextField(draft.summaryPrompt, { draft = draft.copy(summaryPrompt = it) }, Modifier.fillMaxWidth(),
			label = { Text(stringResource(StringsRes.string.feature_settings_highlight_prompt_editable)) }, minLines = 4, maxLines = 10)
		TextButton(onClick = { draft = draft.copy(summaryPrompt = LiveCloudSettings.DEFAULT_SUMMARY_PROMPT) }) { Text(stringResource(StringsRes.string.feature_settings_restore_default_prompt)) }
		MeetingNotesField(draft.meetingNotes) { draft = draft.copy(meetingNotes = it) }

		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_reuse_transcription_api_key_for_summaries, "摘要沿用轉錄 API Key"), draft.summaryReuseTranscribeKey, UiStrings.get(StringsRes.string.feature_settings_choose_explicitly_both_endpoints_must_use_the, "需明確選擇；兩者需是同一服務")) { on ->
			if (on) confirmReuse = true else draft = draft.copy(summaryReuseTranscribeKey = false)
		}
		if (!draft.summaryReuseTranscribeKey) {
			Text(if (hasSummaryKey) UiStrings.get(StringsRes.string.feature_settings_summary_key_saved_separately_in_android_keystore, "摘要 Key：已設定（Android Keystore 加密，獨立於轉錄 Key）") else UiStrings.get(StringsRes.string.feature_settings_no_summary_key_configured, "摘要 Key：尚未設定"), style = MaterialTheme.typography.bodySmall)
			OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), label = { Text(if (hasSummaryKey) UiStrings.get(StringsRes.string.feature_settings_enter_a_replacement_summary_key, "輸入新摘要 Key 以取代") else UiStrings.get(StringsRes.string.feature_settings_paste_summary_api_key, "貼上摘要 API Key")) },
				singleLine = true, visualTransformation = PasswordVisualTransformation(),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Button(enabled = keyInput.isNotBlank(), onClick = { viewModel.saveKey(keyInput); keyInput = "" }) { Text(stringResource(StringsRes.string.feature_settings_save_summary_key)) }
				OutlinedButton(enabled = hasSummaryKey, onClick = { viewModel.removeKey() }) { Text(stringResource(StringsRes.string.feature_settings_remove)) }
			}
		}
		Button(onClick = { viewModel.save(draft.normalized()); msg = UiStrings.get(StringsRes.string.feature_settings_saved_applies_when_the_next_recording_starts, "已儲存（下次開始錄音時生效）") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(StringsRes.string.feature_settings_save)) }
		msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
		val dirty = draft.normalized() != current.normalized() || keyInput.isNotBlank()
		InfoLine(
			if (dirty) UiStrings.get(StringsRes.string.feature_settings_save_changes_before_testing_the_test_uses, "測試只用已儲存設定，請先儲存。") else UiStrings.get(StringsRes.string.feature_settings_sends_one_short_sample_text_no_recording, "送一次短範例文字，不上傳錄音。"),
			UiStrings.get(StringsRes.string.feature_settings_sends_one_summary_request_with_built_in, "測試會用內建短範例文字送出一次摘要請求（可能產生少量費用），不上傳錄音、不自動重試；此模式未開啟也可測試。"),
		)
		Button(enabled = !dirty && !viewModel.testing, onClick = { confirmTest = true }) {
			Text(if (viewModel.testing) UiStrings.get(StringsRes.string.feature_settings_testing_summary, "摘要測試中…") else UiStrings.get(StringsRes.string.feature_settings_test_summary_settings, "測試摘要設定"))
		}
		if (viewModel.testing) TextButton(onClick = { viewModel.cancelTest() }) { Text(stringResource(StringsRes.string.feature_settings_cancel_test)) }
		viewModel.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
		if (draft.enabled) draft.validateSummary(hasSummaryKey, hasAsrKey)?.let {
			Text(stringResource(StringsRes.string.feature_settings_highlights_unavailable_cloud_transcription_will_continue, (it).toString()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
		}
	}

	if (confirmEnable) AlertDialog(
		onDismissRequest = { confirmEnable = false },
		title = { Text(LiveCloudConsentCopy.TITLE) },
		text = {
			Text(LiveCloudConsentCopy.body(draft.transcribeIntervalSec, draft.summaryIntervalSec, draft.summaryModel, draft.enhanceContextQuality))
		},
		confirmButton = {
			TextButton(onClick = {
				draft = draft.copy(enabled = true, consentAcceptedAtMillis = System.currentTimeMillis()); confirmEnable = false
			}) { Text(LiveCloudConsentCopy.CONFIRM) }
		},
		dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text(LiveCloudConsentCopy.CANCEL) } },
	)
	if (confirmTest) AlertDialog(
		onDismissRequest = { confirmTest = false },
		title = { Text(stringResource(StringsRes.string.feature_settings_test_saved_summary_settings)) },
		text = { Text(stringResource(StringsRes.string.feature_settings_sends_one_sample_text_using_your_saved, (current.summaryModel).toString(), (if (current.summaryReuseTranscribeKey) UiStrings.get(StringsRes.string.feature_settings_reused_transcription_key, "沿用的轉錄 Key") else UiStrings.get(StringsRes.string.feature_settings_summary_key, "摘要 Key")).toString(), (SummaryProbe.TOKEN_LIMIT).toString())) },
		confirmButton = { TextButton(onClick = { confirmTest = false; viewModel.testSaved() }) { Text(stringResource(StringsRes.string.feature_settings_accept_charges_and_test_once)) } },
		dismissButton = { TextButton(onClick = { confirmTest = false }) { Text(stringResource(StringsRes.string.feature_settings_cancel)) } },
	)
	if (confirmReuse) AlertDialog(
		onDismissRequest = { confirmReuse = false },
		title = { Text(stringResource(StringsRes.string.feature_settings_reuse_the_transcription_key_for_summaries)) },
		text = { Text(stringResource(StringsRes.string.feature_settings_the_summary_endpoint_will_receive_the_transcription, (draft.summaryBaseUrl.ifBlank { UiStrings.get(StringsRes.string.feature_settings_not_configured, "尚未設定") }).toString())) },
		confirmButton = { TextButton(onClick = { draft = draft.copy(summaryReuseTranscribeKey = true); confirmReuse = false }) { Text(stringResource(StringsRes.string.feature_settings_confirm_reuse)) } },
		dismissButton = { TextButton(onClick = { confirmReuse = false }) { Text(stringResource(StringsRes.string.feature_settings_cancel)) } },
	)
}

/** Same cap the prompt builder enforces; kept here so the field and its UI test share one value. */
internal const val MEETING_NOTES_MAX = LiveCloudSettings.MAX_MEETING_NOTES

/**
 * Optional pre-meeting notes / agenda. The field caps itself at [MEETING_NOTES_MAX] and clears
 * through its own button so the caller only has to keep the string; the player-facing prompt is built elsewhere and
 * only reads the saved value.
 */
@Composable
internal fun MeetingNotesField(notes: String, onNotesChange: (String) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		OutlinedTextField(
			value = notes,
			onValueChange = { onNotesChange(it.take(MEETING_NOTES_MAX)) },
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
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
	var text by remember(value) { mutableStateOf(value.toString()) }
	OutlinedTextField(text, { v -> text = v.filter(Char::isDigit); text.toIntOrNull()?.let(onChange) }, Modifier.fillMaxWidth(),
		label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
