package com.eva.feature_settings.composables.cloudai

import com.eva.feature_settings.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.slack.SlackDeliveryState
import com.eva.cloudtranscribe.slack.SlackMessages
import com.eva.cloudtranscribe.slack.SlackSendPolicy
import com.eva.cloudtranscribe.slack.SlackSendResult
import com.eva.cloudtranscribe.slack.SlackSender
import com.eva.cloudtranscribe.slack.SlackSyncSettings
import com.eva.cloudtranscribe.slack.SlackSyncSettingsRepository
import com.eva.cloudtranscribe.slack.SlackThrottle
import com.eva.cloudtranscribe.slack.SlackWebhookStore
import com.eva.cloudtranscribe.slack.UrlConnectionSlackTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
internal class SlackSyncSettingsViewModel @Inject constructor(
	private val repo: SlackSyncSettingsRepository,
	private val webhookStore: SlackWebhookStore,
	deliveryState: SlackDeliveryState,
) : ViewModel() {

	val settings: StateFlow<SlackSyncSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
	val hasWebhook: StateFlow<Boolean> = webhookStore.hasKey
	val lastError: StateFlow<String?> = deliveryState.lastError

	private val _savedWebhookUrl = MutableStateFlow(webhookStore.read())
	/** Decrypted webhook URL for the settings screen only; never logged. */
	val savedWebhookUrl: StateFlow<String?> = _savedWebhookUrl.asStateFlow()

	var testing by mutableStateOf(false)
		private set
	var testResult by mutableStateOf<String?>(null)
		private set

	/** Saves the pasted URL only when it is a Slack incoming webhook. Shown in settings so it can be verified; never logged. */
	fun saveWebhook(url: String): Boolean {
		if (!SlackMessages.isValidWebhookUrl(url)) return false
		val trimmed = url.trim()
		webhookStore.save(trimmed)
		_savedWebhookUrl.value = webhookStore.read() ?: trimmed
		return true
	}

	/** Clearing the webhook also turns sync off (and drops consent). */
	fun clearWebhook() {
		webhookStore.clear()
		_savedWebhookUrl.value = null
		viewModelScope.launch { repo.update { it.copy(enabled = false) } }
	}

	fun update(transform: (SlackSyncSettings) -> SlackSyncSettings) = viewModelScope.launch { repo.update(transform) }

	/** Enabling is only possible through the consent dialog. */
	fun enableWithConsent() = viewModelScope.launch {
		repo.update { it.copy(enabled = true, consentAtMillis = System.currentTimeMillis()) }
	}

	fun disable() = viewModelScope.launch { repo.update { it.copy(enabled = false) } }

	/** Sends one fixed sentence with no recording content. */
	fun sendTest() {
		if (testing) return
		val url = webhookStore.read() ?: run { testResult = UiStrings.get(StringsRes.string.feature_settings_no_webhook_saved, "尚未儲存 Webhook"); return }
		testing = true
		testResult = null
		viewModelScope.launch {
			try {
				val result = SlackSender(UrlConnectionSlackTransport(), maxInlineWaitSeconds = 0L).send(url, SlackMessages.TEST_TEXT)
				testResult = if (result == SlackSendResult.Sent) UiStrings.get(StringsRes.string.feature_settings_test_message_sent_check_your_slack_channel, "測試訊息已送出，請到 Slack 頻道確認。") else SlackSendPolicy.describe(result)
			} finally {
				testing = false
			}
		}
	}
}

/** "Slack 同步" block in Settings › 雲端. Default OFF; enabling always goes through the consent dialog. */
@Composable
internal fun SlackSyncSettingsSection(viewModel: SlackSyncSettingsViewModel = hiltViewModel()) {
	val saved by viewModel.settings.collectAsStateWithLifecycle()
	val hasWebhook by viewModel.hasWebhook.collectAsStateWithLifecycle()
	val savedWebhookUrl by viewModel.savedWebhookUrl.collectAsStateWithLifecycle()
	val lastError by viewModel.lastError.collectAsStateWithLifecycle()
	val current = saved ?: return
	val context = LocalContext.current
	var urlInput by remember(savedWebhookUrl) { mutableStateOf(savedWebhookUrl.orEmpty()) }
	var urlError by remember { mutableStateOf<String?>(null) }
	var label by remember(current.channelLabel) { mutableStateOf(current.channelLabel) }
	var consent by remember { mutableStateOf(false) }
	var confirmTest by remember { mutableStateOf(false) }

	val status = when {
		current.isActive -> UiStrings.get(StringsRes.string.feature_settings_enabled, "已開啟") + current.channelLabel.takeIf { it.isNotBlank() }?.let { "・$it" }.orEmpty()
		hasWebhook -> UiStrings.get(StringsRes.string.feature_settings_webhook_configured, "已設定 Webhook")
		else -> UiStrings.get(StringsRes.string.feature_settings_not_configured, "尚未設定")
	}
	SettingsCard(UiStrings.get(StringsRes.string.feature_settings_slack_sync, "Slack 同步"), status, "ai-slack") {
		InfoLine(
			UiStrings.get(StringsRes.string.feature_settings_send_live_highlights_to_the_selected_channel, "開啟後把即時重點送到指定頻道。不上傳音訊、不讀取 Slack。"),
			UiStrings.get(StringsRes.string.feature_settings_during_recording_this_device_sends_text_highlights, "開啟後，錄音中會把本機產生的即時重點送到你用 Incoming Webhook 指定的 Slack 頻道。不會上傳音訊，也不會讀取 Slack。") +
				UiStrings.get(StringsRes.string.feature_settings_webhook_messages_cannot_be_edited_or_reliably, "Webhook 的限制：送出後不能編輯，也無法可靠地開討論串。所以「持續送」的做法是：開始一則、之後最多每 N 分鐘一則「新增的重點」、結束一則。"),
		)

		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.Top,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			SelectionContainer(Modifier.weight(1f)) {
				Text(
					if (savedWebhookUrl != null) "Webhook：$savedWebhookUrl" else UiStrings.get(StringsRes.string.feature_settings_no_webhook_configured, "Webhook：尚未設定"),
					style = MaterialTheme.typography.bodyMedium,
				)
			}
			if (savedWebhookUrl != null) TextButton(onClick = {
				val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
				clipboard.setPrimaryClip(ClipData.newPlainText("Slack Webhook", savedWebhookUrl))
				Toast.makeText(context, UiStrings.get(StringsRes.string.feature_settings_copied, "已複製"), Toast.LENGTH_SHORT).show()
			}) { Text(stringResource(StringsRes.string.feature_settings_copy)) }
		}
		OutlinedTextField(
			value = urlInput,
			onValueChange = { urlInput = it; urlError = null },
			label = { Text(if (hasWebhook) "Webhook URL" else UiStrings.get(StringsRes.string.feature_settings_paste_webhook_url, "貼上 Webhook URL")) },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
			modifier = Modifier.fillMaxWidth(),
		)
		urlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(enabled = urlInput.isNotBlank(), onClick = {
				if (!viewModel.saveWebhook(urlInput)) {
					urlError = UiStrings.get(StringsRes.string.feature_settings_use_an_incoming_webhook_url_starting_with, "需要 https://hooks.slack.com/services/… 開頭的 Incoming Webhook URL")
				}
			}) { Text(stringResource(StringsRes.string.feature_settings_save_webhook)) }
			if (hasWebhook) OutlinedButton(onClick = viewModel::clearWebhook) { Text(stringResource(StringsRes.string.feature_settings_clear)) }
		}
		Text(
			if (current.channelLabel.isNotBlank()) UiStrings.get(StringsRes.string.feature_settings_channel_display_name, "頻道顯示名稱：%1\$s", current.channelLabel) else UiStrings.get(StringsRes.string.feature_settings_no_channel_display_name_configured, "頻道顯示名稱：尚未設定"),
			style = MaterialTheme.typography.bodyMedium,
		)
		OutlinedTextField(
			value = label,
			onValueChange = { label = it.take(SlackSyncSettings.MAX_LABEL) },
			label = { Text(stringResource(StringsRes.string.feature_settings_channel_display_name_local_only_e_g)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		if (label != current.channelLabel) TextButton(onClick = { viewModel.update { it.copy(channelLabel = label) } }) { Text(stringResource(StringsRes.string.feature_settings_save_name)) }

		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_enable_slack_sync_alt, "開啟 Slack 同步"), current.isActive) { on ->
			if (on) consent = true else viewModel.disable()
		}
		if (!hasWebhook) Text(stringResource(StringsRes.string.feature_settings_save_a_webhook_before_enabling_sync), style = MaterialTheme.typography.bodySmall)

		Text(stringResource(StringsRes.string.feature_settings_content_to_send_alt), style = MaterialTheme.typography.titleSmall)
		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_recording_start_notification, "錄音開始通知"), current.sendStart) { v -> viewModel.update { it.copy(sendStart = v) } }
		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_live_highlight_updates, "即時重點更新"), current.sendKeyPoints, UiStrings.get(StringsRes.string.feature_settings_up_to_three_unique_highlights_per_batch, "合併去重後每批最多 3 點；需開啟即時雲端摘要")) { v -> viewModel.update { it.copy(sendKeyPoints = v) } }
		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_final_highlights, "結束時的重點總結"), current.sendEnd, UiStrings.get(StringsRes.string.feature_settings_send_a_brief_recap_when_the_cloud, "等錄音後的雲端摘要完成再送一則精簡總結")) { v -> viewModel.update { it.copy(sendEnd = v) } }
		SettingSwitch(UiStrings.get(StringsRes.string.feature_settings_mask_personal_data_before_sending, "送出前遮蔽個資"), current.maskSensitive, UiStrings.get(StringsRes.string.feature_settings_emails_and_long_numbers, "電子郵件與長數字")) { v -> viewModel.update { it.copy(maskSensitive = v) } }
		Text(stringResource(StringsRes.string.feature_settings_live_highlight_interval), style = MaterialTheme.typography.bodyMedium)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			SlackThrottle.OPTIONS_MINUTES.forEach { minutes ->
				val label = if (minutes == 0) UiStrings.get(StringsRes.string.feature_settings_close, "關閉") else UiStrings.get(StringsRes.string.feature_settings_minutes, "%1\$s 分鐘", minutes)
				FilterChip(
					selected = current.normalizedIntervalMinutes == minutes,
					onClick = { viewModel.update { it.copy(minIntervalMinutes = minutes) } },
					label = { Text(label) },
				)
			}
		}

		OutlinedButton(enabled = hasWebhook && !viewModel.testing, onClick = { confirmTest = true }) {
			Text(if (viewModel.testing) UiStrings.get(StringsRes.string.feature_settings_sending, "傳送中…") else UiStrings.get(StringsRes.string.feature_settings_send_test, "測試傳送"))
		}
		viewModel.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
		lastError?.let { Text(stringResource(StringsRes.string.feature_settings_last_sync, (it).toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
		InfoLine(UiStrings.get(StringsRes.string.feature_settings_live_highlights_are_deduplicated_the_final_message, "即時重點只合併去重、不另外壓縮。結束訊息會等錄音後的雲端摘要；失敗或逾時則改用精簡的即時重點。"))
	}

	if (consent) AlertDialog(
		onDismissRequest = { consent = false },
		title = { Text(stringResource(StringsRes.string.feature_settings_enable_slack_sync)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text(stringResource(StringsRes.string.feature_settings_destination, (current.channelLabel.ifBlank { UiStrings.get(StringsRes.string.feature_settings_channel_selected_when_you_created_the_webhook, "（你建立 Webhook 時選的頻道）") }).toString()))
				Text(stringResource(StringsRes.string.feature_settings_content_to_send) + listOfNotNull(
					UiStrings.get(StringsRes.string.feature_settings_recording_start_notification, "錄音開始通知").takeIf { current.sendStart },
					UiStrings.get(StringsRes.string.feature_settings_new_live_highlights_every_minutes_up_to, "新增的即時重點（每 %1\$s 分鐘一批，最多 3 點）", current.normalizedIntervalMinutes).takeIf { current.sendKeyPoints && current.normalizedIntervalMinutes > 0 },
					UiStrings.get(StringsRes.string.feature_settings_final_highlights_after_the_cloud_summary, "結束時的重點總結（等雲端摘要）").takeIf { current.sendEnd },
				).joinToString("、").ifEmpty { UiStrings.get(StringsRes.string.feature_settings_no_content_selected, "（目前沒有勾選任何內容）") })
				Text(stringResource(StringsRes.string.feature_settings_text_derived_from_participants_speech_will_leave))
				Text(stringResource(StringsRes.string.feature_settings_you_can_disable_sync_or_remove_the))
			}
		},
		confirmButton = {
			TextButton(enabled = hasWebhook, onClick = { consent = false; viewModel.enableWithConsent() }) { Text(stringResource(StringsRes.string.feature_settings_agree_and_enable)) }
		},
		dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(StringsRes.string.feature_settings_cancel)) } },
	)
	if (confirmTest) AlertDialog(
		onDismissRequest = { confirmTest = false },
		title = { Text(stringResource(StringsRes.string.feature_settings_send_a_test_message)) },
		text = { Text(stringResource(StringsRes.string.feature_settings_sends_the_fixed_text_to_the_saved, (SlackMessages.TEST_TEXT).toString())) },
		confirmButton = { TextButton(onClick = { confirmTest = false; viewModel.sendTest() }) { Text(stringResource(StringsRes.string.feature_settings_send)) } },
		dismissButton = { TextButton(onClick = { confirmTest = false }) { Text(stringResource(StringsRes.string.feature_settings_cancel)) } },
	)
}

