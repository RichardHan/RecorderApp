package com.eva.feature_recorder.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.eva.cloudtranscribe.slack.SlackSyncStatus

/**
 * Shown only while recording and only when the user has opted into Slack sync in Settings.
 * The switch turns sync off (or back on) for this recording only.
 */
@Composable
internal fun SlackSyncIndicator(
	status: SlackSyncStatus,
	isRecording: Boolean,
	onToggle: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!isRecording || !status.available) return
	val target = status.channelLabel.ifBlank { "Slack" }
	val text = when {
		!status.syncing -> "這次錄音不同步到 Slack"
		status.paused -> "Slack 同步已暫停（錄音暫停中）：$target"
		else -> "正在同步到 Slack：$target"
	}
	Row(
		modifier = modifier.fillMaxWidth().semantics { contentDescription = text },
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(
			text,
			style = MaterialTheme.typography.labelMedium,
			color = if (status.syncing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
		Switch(checked = status.syncing, onCheckedChange = onToggle)
	}
}
