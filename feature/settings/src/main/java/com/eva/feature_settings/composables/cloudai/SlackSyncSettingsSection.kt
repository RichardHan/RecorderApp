package com.eva.feature_settings.composables.cloudai

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
		current.isActive -> "已開啟" + current.channelLabel.takeIf { it.isNotBlank() }?.let { "・$it" }.orEmpty()
		hasWebhook -> "已設定 Webhook"
		else -> "尚未設定"
	}
	SettingsCard("Slack 同步", status, "ai-slack") {
		InfoLine(
			"開啟後把即時重點送到指定頻道。不上傳音訊、不讀取 Slack。",
			"開啟後，錄音中會把本機產生的即時重點送到你用 Incoming Webhook 指定的 Slack 頻道。不會上傳音訊，也不會讀取 Slack。" +
				"Webhook 的限制：送出後不能編輯，也無法可靠地開討論串。所以「持續送」的做法是：開始一則、之後最多每 N 分鐘一則「新增的重點」、結束一則。",
		)

		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.Top,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			SelectionContainer(Modifier.weight(1f)) {
				Text(
					if (savedWebhookUrl != null) "Webhook：$savedWebhookUrl" else "Webhook：尚未設定",
					style = MaterialTheme.typography.bodyMedium,
				)
			}
			if (savedWebhookUrl != null) TextButton(onClick = {
				val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
				clipboard.setPrimaryClip(ClipData.newPlainText("Slack Webhook", savedWebhookUrl))
				Toast.makeText(context, "已複製", Toast.LENGTH_SHORT).show()
			}) { Text("複製") }
		}
		OutlinedTextField(
			value = urlInput,
			onValueChange = { urlInput = it; urlError = null },
			label = { Text(if (hasWebhook) "Webhook URL" else "貼上 Webhook URL") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
			modifier = Modifier.fillMaxWidth(),
		)
		urlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(enabled = urlInput.isNotBlank(), onClick = {
				if (!viewModel.saveWebhook(urlInput)) {
					urlError = "需要 https://hooks.slack.com/services/… 開頭的 Incoming Webhook URL"
				}
			}) { Text("儲存 Webhook") }
			if (hasWebhook) OutlinedButton(onClick = viewModel::clearWebhook) { Text("清除") }
		}
		Text(
			if (current.channelLabel.isNotBlank()) "頻道顯示名稱：${current.channelLabel}" else "頻道顯示名稱：尚未設定",
			style = MaterialTheme.typography.bodyMedium,
		)
		OutlinedTextField(
			value = label,
			onValueChange = { label = it.take(SlackSyncSettings.MAX_LABEL) },
			label = { Text("頻道顯示名稱（只存在本機，例如 #meeting-notes）") },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		if (label != current.channelLabel) TextButton(onClick = { viewModel.update { it.copy(channelLabel = label) } }) { Text("儲存名稱") }

		SettingSwitch("開啟 Slack 同步", current.isActive) { on ->
			if (on) consent = true else viewModel.disable()
		}
		if (!hasWebhook) Text("要先儲存 Webhook 才能開啟。", style = MaterialTheme.typography.bodySmall)

		Text("要送的內容", style = MaterialTheme.typography.titleSmall)
		SettingSwitch("錄音開始通知", current.sendStart) { v -> viewModel.update { it.copy(sendStart = v) } }
		SettingSwitch("即時重點更新", current.sendKeyPoints, "合併去重後每批最多 3 點；需開啟即時雲端摘要") { v -> viewModel.update { it.copy(sendKeyPoints = v) } }
		SettingSwitch("結束時的重點總結", current.sendEnd, "等錄音後的雲端摘要完成再送一則精簡總結") { v -> viewModel.update { it.copy(sendEnd = v) } }
		SettingSwitch("送出前遮蔽個資", current.maskSensitive, "電子郵件與長數字") { v -> viewModel.update { it.copy(maskSensitive = v) } }
		Text("即時重點更新間隔", style = MaterialTheme.typography.bodyMedium)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			SlackThrottle.OPTIONS_MINUTES.forEach { minutes ->
				val label = if (minutes == 0) "關閉" else "${minutes} 分鐘"
				FilterChip(
					selected = current.normalizedIntervalMinutes == minutes,
					onClick = { viewModel.update { it.copy(minIntervalMinutes = minutes) } },
					label = { Text(label) },
				)
			}
		}

		OutlinedButton(enabled = hasWebhook && !viewModel.testing, onClick = { confirmTest = true }) {
			Text(if (viewModel.testing) "傳送中…" else "測試傳送")
		}
		viewModel.testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
		lastError?.let { Text("最近一次同步：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
		InfoLine("即時重點只合併去重、不另外壓縮。結束訊息會等錄音後的雲端摘要；失敗或逾時則改用精簡的即時重點。")
	}

	if (consent) AlertDialog(
		onDismissRequest = { consent = false },
		title = { Text("開啟 Slack 同步？") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				Text("目的地：${current.channelLabel.ifBlank { "（你建立 Webhook 時選的頻道）" }}")
				Text("會送出：" + listOfNotNull(
					"錄音開始通知".takeIf { current.sendStart },
					"新增的即時重點（每 ${current.normalizedIntervalMinutes} 分鐘一批，最多 3 點）".takeIf { current.sendKeyPoints && current.normalizedIntervalMinutes > 0 },
					"結束時的重點總結（等雲端摘要）".takeIf { current.sendEnd },
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

