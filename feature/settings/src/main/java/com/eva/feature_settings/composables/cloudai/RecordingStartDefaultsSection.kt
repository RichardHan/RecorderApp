package com.eva.feature_settings.composables.cloudai

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.eva.cloudtranscribe.live.LiveCloudConsentCopy
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.live.LiveCloudSettingsRepository
import com.eva.cloudtranscribe.slack.SlackSyncSettingsRepository
import com.eva.cloudtranscribe.slack.SlackWebhookStore
import com.eva.datastore.domain.models.TranscriptionSettings
import com.eva.datastore.domain.repository.TranscriptionSettingsRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
internal class RecordingStartDefaultsViewModel @Inject constructor(
	private val transcriptionSettings: TranscriptionSettingsRepo,
	private val liveCloudSettings: LiveCloudSettingsRepository,
	private val slackSettings: SlackSyncSettingsRepository,
	webhookStore: SlackWebhookStore,
) : ViewModel() {
	val local: StateFlow<TranscriptionSettings> = transcriptionSettings.settingsFlow
		.stateIn(viewModelScope, SharingStarted.Eagerly, TranscriptionSettings())
	val liveCloud: StateFlow<LiveCloudSettings?> = liveCloudSettings.settings
		.stateIn(viewModelScope, SharingStarted.Eagerly, null)
	val slack = slackSettings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
	val hasWebhook = webhookStore.hasKey

	fun setLocalDefault(on: Boolean) = viewModelScope.launch { transcriptionSettings.onEnableOrDisable(on) }

	fun setCloudDefault(on: Boolean, consentNow: Boolean = false) = viewModelScope.launch {
		liveCloudSettings.update { cur ->
			if (on) cur.copy(
				enabled = true,
				consentAcceptedAtMillis = cur.consentAcceptedAtMillis ?: if (consentNow) System.currentTimeMillis() else cur.consentAcceptedAtMillis,
			) else cur.copy(enabled = false)
		}
	}

	fun setSlackDefault(on: Boolean, consentNow: Boolean = false) = viewModelScope.launch {
		slackSettings.update { cur ->
			if (on) cur.copy(
				enabled = true,
				consentAtMillis = cur.consentAtMillis ?: if (consentNow) System.currentTimeMillis() else cur.consentAtMillis,
			) else cur.copy(enabled = false)
		}
	}
}

/** Overview of the three recorder-chip defaults. Same backing settings as the detailed sections below. */
@Composable
internal fun RecordingStartDefaultsSection(viewModel: RecordingStartDefaultsViewModel = hiltViewModel()) {
	val local by viewModel.local.collectAsStateWithLifecycle()
	val live by viewModel.liveCloud.collectAsStateWithLifecycle()
	val slack by viewModel.slack.collectAsStateWithLifecycle()
	val hasWebhook by viewModel.hasWebhook.collectAsStateWithLifecycle()
	val cloud = live ?: return
	val slackSettings = slack ?: return
	var confirmCloud by remember { mutableStateOf(false) }
	var confirmSlack by remember { mutableStateOf(false) }

	val cloudDefaultOn = cloud.isActive
	val slackDefaultOn = slackSettings.isActive && hasWebhook
	val status = buildList {
		add("本機字幕" + if (local.isEnabled) "開" else "關")
		add("雲端即時" + if (cloudDefaultOn) "開" else "關")
		add("Slack" + if (slackDefaultOn) "開" else "關")
	}.joinToString("・")

	SettingsCard("錄音開始時預設", status, "ai-rec-defaults") {
		InfoLine(
			"每次新錄音開始時套用；錄音中可在畫面隨時開關，不會改這裡的預設。",
			"本機字幕預設關閉（較耗手機資源）。雲端即時與 Slack 預設跟隨下方的啟用設定。本機模型在字幕關閉時仍可選取。",
		)
		SettingSwitch(
			"本機字幕",
			local.isEnabled,
			"Sherpa／Vosk 即時辨識；預設關閉",
		) { viewModel.setLocalDefault(it) }
		SettingSwitch(
			"雲端即時",
			cloudDefaultOn,
			if (cloudDefaultOn) "定期上傳音訊並整理重點" else "跟隨「即時雲端模式」",
		) { on ->
			if (on && cloud.consentAcceptedAtMillis == null) confirmCloud = true
			else viewModel.setCloudDefault(on)
		}
		SettingSwitch(
			"Slack",
			slackDefaultOn,
			when {
				!hasWebhook || !slackSettings.isActive -> "未設定（需 Webhook 與同意）"
				else -> "有新重點時同步"
			},
		) { on ->
			if (!hasWebhook) return@SettingSwitch
			if (on && slackSettings.consentAtMillis == null) confirmSlack = true
			else viewModel.setSlackDefault(on)
		}
	}

	if (confirmCloud) AlertDialog(
		onDismissRequest = { confirmCloud = false },
		title = { Text(LiveCloudConsentCopy.TITLE) },
		text = { Text(LiveCloudConsentCopy.body(cloud.transcribeIntervalSec, cloud.summaryIntervalSec, cloud.summaryModel)) },
		confirmButton = {
			TextButton(onClick = { confirmCloud = false; viewModel.setCloudDefault(true, consentNow = true) }) {
				Text(LiveCloudConsentCopy.CONFIRM)
			}
		},
		dismissButton = { TextButton(onClick = { confirmCloud = false }) { Text(LiveCloudConsentCopy.CANCEL) } },
	)
	if (confirmSlack) AlertDialog(
		onDismissRequest = { confirmSlack = false },
		title = { Text("開啟 Slack 同步？") },
		text = { Text("錄音開始後會依你的 Slack 設定送出開始通知與即時重點。只送出本機產生的文字，不會上傳錄音。") },
		confirmButton = {
			TextButton(onClick = { confirmSlack = false; viewModel.setSlackDefault(true, consentNow = true) }) {
				Text("我了解，開啟")
			}
		},
		dismissButton = { TextButton(onClick = { confirmSlack = false }) { Text("取消") } },
	)
}
