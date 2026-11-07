package com.eva.feature_settings.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eva.ui.R

internal enum class SettingsTabs(
	val tabIndex: Int,
) {
	AUDIO_SETTINGS(0),
	FILES_SETTINGS(1),
	TRANSCRIPTIONS_SETTINGS(2),
	CLOUD_SETTINGS(3),
	AI_SETTINGS(4);

	val textRes: String
		@Composable
		get() = when (this) {
			AUDIO_SETTINGS -> stringResource(R.string.app_settings_audio)
			FILES_SETTINGS -> stringResource(R.string.app_settings_files)
			CLOUD_SETTINGS -> stringResource(R.string.drive_settings_title)
			AI_SETTINGS -> "AI"
			else -> stringResource(R.string.app_settings_transcriptions)
		}

	/** Short label so all five tabs fit in one row on a narrow phone. */
	val shortLabel: String
		get() = when (this) {
			AUDIO_SETTINGS -> "Audio"
			FILES_SETTINGS -> "Files"
			TRANSCRIPTIONS_SETTINGS -> "Transcribe"
			CLOUD_SETTINGS -> "Cloud"
			AI_SETTINGS -> "AI"
		}
}
