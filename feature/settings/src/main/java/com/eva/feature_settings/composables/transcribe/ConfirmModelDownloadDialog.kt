package com.eva.feature_settings.composables.transcribe

import com.eva.transcribe.domain.models.localizedName

import com.eva.feature_settings.R as StringsRes
import androidx.compose.ui.res.stringResource

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eva.transcribe.domain.models.STTModel

/** Every model download starts here: shows the real download size and needs an explicit tap for mobile data. */
@Composable
internal fun ConfirmModelDownloadDialog(model: STTModel, onDismiss: () -> Unit, onConfirm: (allowMetered: Boolean) -> Unit) {
	val size = Formatter.formatShortFileSize(LocalContext.current, model.archiveSize)
	val large = model.archiveSize >= 1_500_000_000L
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(StringsRes.string.feature_settings_download_alt, (model.localizedName()).toString())) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(stringResource(StringsRes.string.feature_settings_download_size_wi_fi_or_an_unmetered, (size).toString()))
				if (large) Text(stringResource(StringsRes.string.feature_settings_experimental_large_model_requires_a_64_bit))
				Text(stringResource(StringsRes.string.feature_settings_cancel_at_any_time_downloaded_data_is))
			}
		},
		confirmButton = { TextButton(onClick = { onConfirm(false) }) { Text(stringResource(StringsRes.string.feature_settings_download_on_wi_fi_only)) } },
		dismissButton = {
			Column {
				TextButton(onClick = { onConfirm(true) }) { Text(stringResource(StringsRes.string.feature_settings_allow_mobile_data)) }
				TextButton(onClick = onDismiss) { Text(stringResource(StringsRes.string.feature_settings_cancel)) }
			}
		},
	)
}
