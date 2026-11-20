package com.eva.feature_recorder.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.live.LiveCloudConsentCopy
import com.eva.cloudtranscribe.live.LiveToggleKind
import com.eva.recorder.domain.live.RecorderLiveToggleUi

/**
 * Compact 本機字幕 / 雲端即時 / Slack chips. Mid-recording on/off applies immediately and is also
 * written back as the next-recording start default. First-time cloud or Slack enable shows
 * the existing consent dialog; missing consent is never bypassed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecorderLiveToggleChips(
	ui: RecorderLiveToggleUi,
	onToggleLocal: (Boolean) -> Unit,
	onToggleCloud: (Boolean) -> Unit,
	onToggleSlack: (Boolean) -> Unit,
	onNeedLocalModel: () -> Unit,
	onAcceptCloudConsent: () -> Unit,
	onAcceptSlackConsent: () -> Unit = {},
	modifier: Modifier = Modifier,
) {
	var confirmCloud by rememberSaveable { mutableStateOf(false) }
	var confirmSlack by rememberSaveable { mutableStateOf(false) }
	var needModel by rememberSaveable { mutableStateOf(false) }

	if (needModel) {
		AlertDialog(
			onDismissRequest = { needModel = false },
			title = { Text("尚未選本機模型") },
			text = { Text("請先在設定中選擇並下載離線辨識模型。模型可在本機字幕關閉時選取。") },
			confirmButton = {
				TextButton(onClick = { needModel = false; onNeedLocalModel() }) { Text("開啟設定") }
			},
			dismissButton = { TextButton(onClick = { needModel = false }) { Text("取消") } },
		)
	}

	if (confirmCloud) {
		AlertDialog(
			onDismissRequest = { confirmCloud = false },
			title = { Text(LiveCloudConsentCopy.TITLE) },
			text = {
				Text(
					LiveCloudConsentCopy.body(
						ui.inputs.cloudTranscribeIntervalSec,
						ui.inputs.cloudSummaryIntervalSec,
						ui.inputs.cloudSummaryModel,
						ui.inputs.cloudEnhanceContextQuality,
					)
				)
			},
			confirmButton = {
				TextButton(onClick = { confirmCloud = false; onAcceptCloudConsent() }) {
					Text(LiveCloudConsentCopy.CONFIRM)
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmCloud = false }) { Text(LiveCloudConsentCopy.CANCEL) }
			},
		)
	}

	if (confirmSlack) {
		AlertDialog(
			onDismissRequest = { confirmSlack = false },
			title = { Text("開啟 Slack 同步？") },
			text = { Text("錄音開始後會依你的 Slack 設定送出開始通知與即時重點。只送出本機產生的文字，不會上傳錄音。") },
			confirmButton = {
				TextButton(onClick = { confirmSlack = false; onAcceptSlackConsent() }) {
					Text("我了解，開啟")
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmSlack = false }) { Text("取消") }
			},
		)
	}

	Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
		FlowRow(
			horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
			verticalArrangement = Arrangement.Center,
			itemVerticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.fillMaxWidth(),
		) {
			LiveToggleChip(
				label = "本機字幕",
				selected = ui.localOn,
				enabled = ui.localEnabled,
				hint = ui.localHint,
				onClick = {
					if (ui.localHint == com.eva.cloudtranscribe.live.RecorderLiveTogglePolicy.HINT_NO_MODEL && !ui.localOn)
						needModel = true
					else onToggleLocal(!ui.localOn)
				},
			)
			LiveToggleChip(
				label = "雲端即時",
				selected = ui.cloudOn,
				enabled = ui.cloudEnabled,
				hint = ui.cloudHint,
				onClick = {
					if (!ui.cloudEnabled) return@LiveToggleChip
					if (!ui.cloudOn && !ui.inputs.cloudHasConsent) confirmCloud = true
					else onToggleCloud(!ui.cloudOn)
				},
			)
			LiveToggleChip(
				label = "Slack",
				selected = ui.slackOn,
				enabled = ui.slackEnabled,
				hint = ui.slackHint,
				onClick = {
					if (!ui.slackEnabled) return@LiveToggleChip
					if (!ui.slackOn && !ui.inputs.slackHasConsent) confirmSlack = true
					else onToggleSlack(!ui.slackOn)
				},
			)
		}
		val caption = listOfNotNull(
			ui.slackHint?.takeIf { it == com.eva.cloudtranscribe.live.RecorderLiveTogglePolicy.HINT_SLACK_NEEDS_CLOUD },
			ui.cloudHint,
			ui.localHint,
			ui.slackHint?.takeIf { it == com.eva.cloudtranscribe.live.RecorderLiveTogglePolicy.HINT_UNSET },
		).firstOrNull()
		if (caption != null) {
			Text(
				caption,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun LiveToggleChip(
	label: String,
	selected: Boolean,
	enabled: Boolean,
	hint: String?,
	onClick: () -> Unit,
) {
	val state = if (!enabled) "未設定" else if (selected) "開" else "關"
	val description = buildString {
		append(label)
		append("：")
		append(state)
		hint?.let { append("，$it") }
	}
	FilterChip(
		selected = selected,
		onClick = onClick,
		enabled = enabled,
		label = { Text("$label ${if (selected) "開" else "關"}", style = MaterialTheme.typography.labelMedium) },
		modifier = androidx.compose.ui.Modifier.semantics { contentDescription = description },
	)
}

/** Chip labels used by tests. */
internal fun liveToggleChipLabel(kind: LiveToggleKind, on: Boolean): String = when (kind) {
	LiveToggleKind.LOCAL -> if (on) "本機字幕 開" else "本機字幕 關"
	LiveToggleKind.CLOUD -> if (on) "雲端即時 開" else "雲端即時 關"
	LiveToggleKind.SLACK -> if (on) "Slack 開" else "Slack 關"
}
