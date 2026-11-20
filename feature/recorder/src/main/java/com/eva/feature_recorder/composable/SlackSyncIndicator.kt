package com.eva.feature_recorder.composable

import com.eva.feature_recorder.R as StringsRes
import com.eva.utils.UiStrings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.slack.SlackSyncStatus

/**
 * Compact one-line Slack indicator while recording, only if Slack sync is opted in.
 * Tapping toggles sync for this recording only.
 */
@Composable
internal fun SlackSyncIndicator(
	status: SlackSyncStatus,
	isRecording: Boolean,
	onToggle: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!isRecording || !status.available) return
	val label = when {
		!status.syncing -> UiStrings.get(StringsRes.string.feature_recorder_skip_sync_this_time, "⇄ 這次不同步")
		status.paused -> UiStrings.get(StringsRes.string.feature_recorder_slack_paused, "⇄ Slack 已暫停")
		else -> UiStrings.get(StringsRes.string.feature_recorder_syncing_to_slack, "⇄ Slack 同步中")
	}
	val description = buildString {
		append(label)
		status.channelLabel.takeIf { it.isNotBlank() }?.let { append("：$it") }
	}
	FilterChip(
		selected = status.syncing,
		onClick = { onToggle(!status.syncing) },
		label = { Text(label, style = MaterialTheme.typography.labelMedium) },
		modifier = modifier.semantics { contentDescription = description },
	)
}
