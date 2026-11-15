package com.eva.feature_recorder.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.eva.ui.R

/**
 * One fixed bottom row while recording or paused:
 * left = 標記 (recording) or 捨棄 (paused), center = large pause / 繼續, right = 儲存.
 */
@Composable
internal fun RecorderPausePlayAction(
	showPausedAction: Boolean,
	onResume: () -> Unit,
	onPause: () -> Unit,
	onCancel: () -> Unit,
	onStop: () -> Unit,
	modifier: Modifier = Modifier,
	onAddBookMark: () -> Unit = {},
	enabled: Boolean = true,
) {
	var showCancelDialog by remember { mutableStateOf(false) }
	var showSaveDialog by remember { mutableStateOf(false) }

	CancelRecordingDialog(
		showDialog = showCancelDialog,
		onDismiss = { showCancelDialog = false },
		onDiscard = onCancel,
	)
	SaveRecordingDialog(
		showDialog = showSaveDialog,
		onDismiss = { showSaveDialog = false },
		onSave = onStop,
	)

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.SpaceEvenly,
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (showPausedAction) {
			LabeledRoundButton(
				icon = painterResource(R.drawable.ic_close),
				label = "捨棄",
				enabled = enabled,
				onClick = {
					onPause()
					showCancelDialog = true
				},
			)
		} else {
			LabeledRoundButton(
				icon = painterResource(R.drawable.ic_boomark_add),
				label = "標記",
				enabled = enabled,
				onClick = onAddBookMark,
			)
		}
		PrimaryPauseResume(
			paused = showPausedAction,
			enabled = enabled,
			onResume = onResume,
			onPause = onPause,
		)
		LabeledRoundButton(
			icon = painterResource(R.drawable.ic_check_plain),
			label = "儲存",
			enabled = enabled,
			onClick = {
				onPause()
				showSaveDialog = true
			},
		)
	}
}

@Composable
private fun PrimaryPauseResume(
	paused: Boolean,
	enabled: Boolean,
	onResume: () -> Unit,
	onPause: () -> Unit,
) {
	val red = colorResource(R.color.recorder_button_color)
	Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
		FilledIconButton(
			onClick = if (paused) onResume else onPause,
			enabled = enabled,
			modifier = Modifier.size(72.dp),
			colors = IconButtonDefaults.filledIconButtonColors(containerColor = red, contentColor = Color.White),
		) {
			Icon(
				painter = painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause),
				contentDescription = if (paused) "繼續" else "暫停",
				modifier = Modifier.size(32.dp),
			)
		}
		if (paused) Text("繼續", style = MaterialTheme.typography.labelSmall)
		else Spacer(Modifier.height(16.dp))
	}
}

@Composable
private fun LabeledRoundButton(
	icon: Painter,
	label: String,
	onClick: () -> Unit,
	enabled: Boolean,
) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
		FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp)) {
			Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
		}
		Text(label, style = MaterialTheme.typography.labelSmall)
	}
}
