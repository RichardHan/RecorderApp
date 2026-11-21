package com.eva.feature_recorder.composable

import com.eva.feature_recorder.R as StringsRes
import com.eva.utils.UiStrings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eva.datastore.domain.models.TranscriptionSettings
import com.eva.ui.R

@Composable
internal fun AssociatedRecorderActions(
	onOpenSettings: () -> Unit,
	modifier: Modifier = Modifier,
	transcriptionSettings: TranscriptionSettings = TranscriptionSettings(),
	onToggleLiveTranscription: (Boolean) -> Unit = {},
) {

	var showDialog by rememberSaveable { mutableStateOf(false) }

	TranscriptionsDialog(
		showDialog = showDialog,
		onOpenSettings = {
			onOpenSettings()
			showDialog = false
		},
		transcriptionSettings = transcriptionSettings,
		onDismiss = { showDialog = false },
		iconContentColor = MaterialTheme.colorScheme.primary,
	)

	Row(
		modifier = modifier,
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// real on/off switch for live on-device captions (persists the preference; service follows it live)
		val hasModel = transcriptionSettings.modelId != null
		val isOn = transcriptionSettings.isEnabled && hasModel
		FilterChip(
			selected = isOn,
			onClick = {
				if (!hasModel) showDialog = true // no model: explain + open settings, never auto-download
				else onToggleLiveTranscription(!transcriptionSettings.isEnabled)
			},
			leadingIcon = {
				Icon(
					painter = painterResource(R.drawable.ic_stt),
					contentDescription = null,
					modifier = Modifier.size(18.dp),
				)
			},
			label = { Text(liveToggleLabel(transcriptionSettings)) },
			shape = MaterialTheme.shapes.extraLarge,
		)
	}
}


@Composable
private fun TranscriptionsDialog(
	showDialog: Boolean,
	onDismiss: () -> Unit,
	onOpenSettings: () -> Unit,
	modifier: Modifier = Modifier,
	transcriptionSettings: TranscriptionSettings = TranscriptionSettings(),
	shape: Shape = AlertDialogDefaults.shape,
	tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
	containerColor: Color = AlertDialogDefaults.containerColor,
	titleContentColor: Color = AlertDialogDefaults.titleContentColor,
	textContentColor: Color = AlertDialogDefaults.textContentColor,
	iconContentColor: Color = AlertDialogDefaults.iconContentColor,
	properties: DialogProperties = DialogProperties()
) {

	if (!showDialog) return

	AlertDialog(
		onDismissRequest = onDismiss,
		confirmButton = {
			AnimatedVisibility(visible = !transcriptionSettings.isEnabled || transcriptionSettings.modelId == null) {
				Button(
					onClick = onOpenSettings,
					colors = ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.primaryContainer,
						contentColor = MaterialTheme.colorScheme.onPrimaryContainer
					)
				) {
					Text(stringResource(StringsRes.string.feature_recorder_open_settings))
				}
			}
		},
		title = { Text(text = stringResource(R.string.recorder_transcriptions_about_dialog_title)) },
		text = {
			if (transcriptionSettings.isEnabled && transcriptionSettings.modelId != null)
				Text(
					text = buildAnnotatedString {
						append(stringResource(R.string.recorder_transcriptions_about_dialog_text_enabled))
						withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
							append(" ${transcriptionSettings.modelId}")
						}
					}
				)
			else Text(stringResource(R.string.recorder_transcriptions_about_dialog_text_not_enabled))
		},
		icon = {
			Icon(
				painter = painterResource(R.drawable.ic_stt),
				contentDescription = null, modifier = Modifier.size(32.dp),
			)
		},
		modifier = modifier,
		shape = shape,
		tonalElevation = tonalElevation,
		containerColor = containerColor,
		textContentColor = textContentColor,
		titleContentColor = titleContentColor,
		iconContentColor = iconContentColor,
		properties = properties
	)
}

/** Chip label; pure for tests. */
internal fun liveToggleLabel(s: TranscriptionSettings): String = when {
	s.modelId == null -> UiStrings.get(StringsRes.string.feature_recorder_live_captions_no_model_selected, "即時字幕：未選模型")
	s.isEnabled -> UiStrings.get(StringsRes.string.feature_recorder_live_captions_on, "即時字幕：開")
	else -> UiStrings.get(StringsRes.string.feature_recorder_live_captions_off, "即時字幕：關")
}
