package com.eva.feature_settings.composables.audio

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.eva.feature_settings.R as SettingsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.eva.datastore.domain.models.RecorderAudioSettings
import com.eva.feature_settings.composables.SettingsItemTitle
import com.eva.feature_settings.composables.SettingsItemWithSwitch
import com.eva.feature_settings.utils.AudioSettingsEvent
import com.eva.ui.R
import com.eva.ui.theme.RecorderAppTheme

@Composable
internal fun AudioSettingsTabContent(
	settings: RecorderAudioSettings,
	onEvent: (AudioSettingsEvent) -> Unit,
	modifier: Modifier = Modifier,
	contentPadding: PaddingValues = PaddingValues(12.dp),
	deviceContextSettings: com.eva.cloudtranscribe.device.DeviceContextSettings = com.eva.cloudtranscribe.device.DeviceContextSettings(),
) {

	val isInspectionMode = LocalInspectionMode.current
	val context = LocalContext.current

	var hasLocationPermission by remember(context) {
		mutableStateOf(
			if (isInspectionMode) false else (
				ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
					ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
				)
		)
	}
	val locationLauncher = rememberLauncherForActivityResult(
		contract = ActivityResultContracts.RequestMultiplePermissions()
	) { perms ->
		hasLocationPermission =
			perms.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
			perms.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
	}

	val isIgnoreOptimization = remember(context) {
		if (isInspectionMode) return@remember false
		val powerManager = context.getSystemService<PowerManager>()
		powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
	}

	LazyColumn(
		modifier = modifier.fillMaxSize(),
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		item {
			TextButton(onClick = {
				val systemLanguages = Intent(Settings.ACTION_LOCALE_SETTINGS)
				val intent = if (Build.VERSION.SDK_INT >= 33) {
					Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
				} else systemLanguages
				try {
					context.startActivity(intent)
				} catch (_: android.content.ActivityNotFoundException) {
					context.startActivity(systemLanguages)
				}
			}) { Text(stringResource(SettingsRes.string.feature_settings_app_language)) }
		}
		if (!isIgnoreOptimization) {
			item {
				IgnoreBatteryOptimizationCard(
					modifier = Modifier.animateItem()
				)
			}
		}
		item {
			SettingsItemTitle(
				title = stringResource(id = R.string.recording_settings_encoder_title),
				text = stringResource(id = R.string.recording_settings_encoder_text),
			)
		}
		item {
			AudioEncoderSelector(
				encoder = settings.encoders,
				onEncoderChange = { onEvent(AudioSettingsEvent.OnEncoderChange(it)) },
			)
		}
		item {
			SettingsItemTitle(
				title = stringResource(id = R.string.recording_settings_quality_title),
				text = stringResource(id = R.string.recording_settings_quality_text)
			)
		}
		item {
			AudioQualitySelector(
				quality = settings.quality,
				onQualityChanged = { onEvent(AudioSettingsEvent.OnQualityChange(it)) },
				modifier = Modifier.fillMaxWidth(),
				contentPadding = PaddingValues(horizontal = 12.dp),
			)
		}
		item {
			SettingsItemTitle(
				title = stringResource(id = R.string.recording_settings_features_title),
				text = stringResource(id = R.string.recording_settings_features_text)
			)
		}
		item {
			SettingsItemWithSwitch(
				isSelected = settings.enableStereo,
				title = stringResource(id = R.string.recording_settings_enable_stereo),
				text = stringResource(id = R.string.recording_settings_enable_stereo_text),
				leading = {
					Icon(
						painter = painterResource(id = R.drawable.ic_stereo),
						contentDescription = stringResource(id = R.string.recording_settings_skip_silences_title),
					)
				},
				onSelect = { onEvent(AudioSettingsEvent.OnStereoModeChange(it)) },
			)
		}
		item {
			SettingsItemWithSwitch(
				isSelected = settings.skipSilences,
				title = stringResource(id = R.string.recording_settings_skip_silences_title),
				text = stringResource(id = R.string.recording_settings_skip_silences_text),
				leading = {
					Icon(
						painter = painterResource(id = R.drawable.ic_silence),
						contentDescription = stringResource(id = R.string.recording_settings_skip_silences_title),
					)
				},
				onSelect = {
					onEvent(AudioSettingsEvent.OnSkipSilencesChange(it))
				},
			)
		}
		item {
			PauseRecorderOnCallTile(
				isPauseRecordingOnIncommingCall = settings.pauseRecordingOnCall,
				onActionEnabledChanged = { onEvent(AudioSettingsEvent.OnPauseRecorderOnCalls(it)) })
		}
		item {
			LocationInfoCollectionCard(
				isAddLocationInfoAllowed = settings.addLocationInfoInRecording,
				onActionEnabledChanged = { onEvent(AudioSettingsEvent.OnAddLocationEnabled(it)) })
		}
		item {
			SettingsItemWithSwitch(
				isSelected = deviceContextSettings.enabled,
				title = stringResource(SettingsRes.string.feature_settings_record_device_context_title),
				text = stringResource(SettingsRes.string.feature_settings_record_device_context_text),
				leading = {
					Icon(
						painter = painterResource(id = R.drawable.ic_info),
						contentDescription = stringResource(SettingsRes.string.feature_settings_record_device_context_title),
					)
				},
				onSelect = { onEvent(AudioSettingsEvent.OnDeviceContextEnabled(it)) },
			)
		}
		if (deviceContextSettings.enabled) {
			item {
				SettingsItemWithSwitch(
					isSelected = deviceContextSettings.includeLocation,
					title = stringResource(SettingsRes.string.feature_settings_record_device_location_title),
					text = stringResource(
						if (hasLocationPermission) SettingsRes.string.feature_settings_record_device_location_text
						else SettingsRes.string.feature_settings_record_device_location_no_permission
					),
					leading = {
						Icon(
							painter = painterResource(id = R.drawable.ic_location),
							contentDescription = stringResource(SettingsRes.string.feature_settings_record_device_location_title),
						)
					},
					onSelect = { enabled ->
						onEvent(AudioSettingsEvent.OnDeviceContextIncludeLocation(enabled))
						if (enabled && !hasLocationPermission) {
							locationLauncher.launch(
								arrayOf(
									Manifest.permission.ACCESS_FINE_LOCATION,
									Manifest.permission.ACCESS_COARSE_LOCATION,
								)
							)
						}
					},
				)
			}
		}
		item {
			SettingsItemWithSwitch(
				isSelected = settings.useBluetoothMic,
				title = stringResource(id = R.string.recording_settings_use_bt_mic),
				text = stringResource(id = R.string.recording_settings_use_bt_mic_text),
				leading = {
					Icon(
						painter = painterResource(id = R.drawable.ic_bluetooth),
						contentDescription = stringResource(id = R.string.recording_settings_use_bt_mic),
					)
				},
				onSelect = {
					onEvent(AudioSettingsEvent.OnUseBluetoothMicChanged(it))
				},
			)
		}
	}
}

@PreviewLightDark
@Composable
private fun AudioSettingsTabContentPreview() = RecorderAppTheme {
	Surface {
		AudioSettingsTabContent(
			settings = RecorderAudioSettings(),
			onEvent = {},
		)
	}
}