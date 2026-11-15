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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

	var testing by mutableStateOf(false)
		private set
	var testResult by mutableStateOf<String?>(null)
		private set

	/** Saves the pasted URL only when it is a Slack incoming webhook; the URL is never shown again or logged. */
	fun saveWebhook(url: String): Boolean {
		if (!SlackMessages.isValidWebhookUrl(url)) return false
		webhookStore.save(url.trim())
		return true
	}

	/** Clearing the webhook also turns sync off (and drops consent). */
	fun clearWebhook() {
		webhookStore.clear()
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
		val url = webhookStore.read() ?: run { testResult = "尚未儲存 Webhook"; return }
		testing = true
		testResult = null
		viewModelScope.launch {
			try {
				val result = SlackSender(UrlConnectionSlackTransport(), maxInlineWaitSeconds = 0L).send(url, SlackMessages.TEST_TEXT)
				testResult = if (result == SlackSendResult.Sent) "測試訊息已送出，請到 Slack 頻道確認。" else SlackSendPolicy.describe(result)
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
	val lastError by viewModel.lastError.collectAsStateWithLifecycle()
	val current = saved ?: return
	var urlInput by remember { mutableStateOf("") }
	var urlError by remember { mutableStateOf<String?>(null) }
	var label by remember(current.channelLabel) { mutableStateOf(current.channelLabel) }
	var consent by remember { mutableStateOf(false) }
	var confirmTest by remember { mutableStateOf(false) }

	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		HorizontalDivider()
		Text("Slack 同步（選用，預設關閉）", style = MaterialTheme.typography.titleMedium)
		Text(
			"開啟後，錄音中會把本機產生的即時重點送到你用 Incoming Webhook 指定的 Slack 頻道。不會上傳音訊，也不會讀取 Slack。",
			style = MaterialTheme.typography.bodySmall,
		)
		Text(
			"Webhook 的限制：送出後不能編輯，也無法可靠地開討論串。所以「持續送」的做法是：開始一則、之後最多每 N 分鐘一則「新增的重點」、結束一則。",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		Text(if (hasWebhook) "Webhook：已儲存（不顯示網址）" else "Webhook：尚未設定", style = MaterialTheme.typography.bodyMedium)
		OutlinedTextField(
			value = urlInput,
			onValueChange = { urlInput = it; urlError = null },
			label = { Text(if (hasWebhook) "更換 Webhook URL" else "貼上 Webhook URL") },
			singleLine = true,
			visualTransformation = PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
			modifier = Modifier.fillMaxWidth(),
		)
		urlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(enabled = urlInput.isNotBlank(), onClick = {
				if (viewModel.saveWebhook(urlInput)) urlInput = "" else urlError = "需要 https://hooks.slack.com/services/… 開頭的 Incoming Webhook URL"
			}) { Text("儲存 Webhook") }
			if (hasWebhook) OutlinedButton(onClick = viewModel::clearWebhook) { Text("清除") }
		}
		OutlinedTextField(
			value = label,
			onValueChange = { label = it.take(SlackSyncSettings.MAX_LABEL) },
			label = { Text("頻道顯示名稱（只存在本機，例如 #meeting-notes）") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		if (label != current.channelLabel) TextButton(onClick = { viewModel.update { it.copy(channelLabel = label) } }) { Text("儲存名稱") }

		SlackRow("開啟 Slack 同步", current.isActive) { on ->
			if (on) consent = true else viewModel.disable()
		}
		if (!hasWebhook) Text("要先儲存 Webhook 才能開啟。", style = MaterialTheme.typography.bodySmall)

		Text("要送的內容", style = MaterialTheme.typography.titleSmall)
		SlackRow("錄音開始通知", current.sendStart) { v -> viewModel.update { it.copy(sendStart = v) } }
		SlackRow("即時重點更新（只送新增的重點；需要已開啟即時雲端摘要）", current.sendKeyPoints) { v -> viewModel.update { it.copy(sendKeyPoints = v) } }
		SlackRow("結束時的重點總結（沒有重點就不送）", current.sendEnd) { v -> viewModel.update { it.copy(sendEnd = v) } }
		SlackRow("送出前遮蔽電子郵件與長數字", current.maskSensitive) { v -> viewModel.update { it.copy(maskSensitive = v) } }
		Text("重點更新最短間隔：${current.normalizedIntervalMinutes} 分鐘", style = MaterialTheme.typography.bodyMedium)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(
				enabled = current.normalizedIntervalMinutes > SlackThrottle.MIN_INTERVAL_MINUTES,
				onClick = { viewModel.update { it.copy(minIntervalMinutes = it.normalizedIntervalMinutes - 1) } },
			) { Text("−") }
			OutlinedButton(
				enabled = current.normalizedIntervalMinutes < SlackThrottle.MAX_INTERVAL_MINUTES,
				onClick = { viewModel.update { it.copy(minIntervalMinutes = it.normalizedIntervalMinutes + 1) } },
			) { Text("＋") }
		}

		OutlinedButton(enabled = hasWebhook && !viewModel.testing, onClick = { confirmTest = true }) {
			Text(if (viewModel.testing) "傳送中…" else "測試傳送")
		}
		viewModel.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
		lastError?.let { Text("最近一次同步：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
		Text("Slack 本身不按則收費；重點內容來自既有的即時雲端摘要，Slack 同步不會另外呼叫模型。", style = MaterialTheme.typography.bodySmall)
	}

	if (consent) AlertDialog(
		onDismissRequest = { consent = false },
		title = { Text("開啟 Slack 同步？") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text("目的地：${current.channelLabel.ifBlank { "（你建立 Webhook 時選的頻道）" }}")
				Text("會送出：" + listOfNotNull(
					"錄音開始通知".takeIf { current.sendStart },
					"新增的即時重點（最多每 ${current.normalizedIntervalMinutes} 分鐘一則）".takeIf { current.sendKeyPoints },
					"結束時的重點總結".takeIf { current.sendEnd },
				).joinToString("、").ifEmpty { "（目前沒有勾選任何內容）" })
				Text("與會者說的話轉成的文字重點會離開這台手機，進入該 Slack 工作區。不會上傳音訊檔。")
				Text("隨時可以在這裡關閉或清除 Webhook；已送到 Slack 的訊息無法由 App 刪除。")
			}
		},
		confirmButton = {
			TextButton(enabled = hasWebhook, onClick = { consent = false; viewModel.enableWithConsent() }) { Text("同意並啟用") }
		},
		dismissButton = { TextButton(onClick = { consent = false }) { Text("取消") } },
	)
	if (confirmTest) AlertDialog(
		onDismissRequest = { confirmTest = false },
		title = { Text("送出測試訊息？") },
		text = { Text("會送一則固定文字「${SlackMessages.TEST_TEXT}」到已儲存的 Webhook，不含任何錄音內容。") },
		confirmButton = { TextButton(onClick = { confirmTest = false; viewModel.sendTest() }) { Text("送出") } },
		dismissButton = { TextButton(onClick = { confirmTest = false }) { Text("取消") } },
	)
}

@Composable
private fun SlackRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
		Text(label, Modifier.weight(1f))
		Switch(checked, onChange)
	}
}
