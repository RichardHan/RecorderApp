package com.eva.feature_player.composable

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.device.DeviceContextFormatter
import com.eva.cloudtranscribe.device.RecordingDeviceContext
import com.eva.feature_player.R as PlayerStrings
import com.eva.player_shared.util.PlayerPreviewFakes
import com.eva.recordings.domain.models.AudioFileModel
import com.eva.ui.R
import com.eva.ui.theme.RecorderAppTheme
import com.eva.utils.LocalTimeFormats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.datetime.format

@Composable
fun FileMetaDataSheetContent(
	audio: AudioFileModel,
	modifier: Modifier = Modifier,
	contentPadding: PaddingValues = PaddingValues(),
	deviceContext: RecordingDeviceContext? = null,
	onOpenChat: (() -> Unit)? = null,
) {

	val context = LocalContext.current

	val fileSize = remember(audio.size) {
		Formatter.formatFileSize(context, audio.size)
	}

	val durationText = remember(audio.duration) {
		audio.durationAsLocaltime.format(LocalTimeFormats.LOCALTIME_HH_MM_SS_FORMAT)
	}

	val lastModified = remember(audio.lastModified) {
		audio.lastModified.format(LocalTimeFormats.LOCAL_DATETIME_DATE_TIME_FORMAT)
	}

	LazyColumn(
		modifier = modifier,
		contentPadding = contentPadding,
		verticalArrangement = Arrangement.spacedBy(4.dp)
	) {
		stickyHeader {
			Text(
				text = stringResource(id = R.string.meta_data_sheet_heading),
				style = MaterialTheme.typography.titleLarge,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.padding(vertical = 4.dp)
			)
		}

		item {
			FileMetaData(
				title = stringResource(id = R.string.audio_metadata_name_title),
				text = audio.displayName
			)
		}
		item {
			FileMetaData(
				title = stringResource(id = R.string.audio_metadata_file_size_title),
				text = fileSize
			)
		}
		item {
			FileMetaData(
				title = stringResource(id = R.string.audio_metadata_duration_title),
				text = durationText
			)
		}
		item {
			FileMetaData(
				title = stringResource(id = R.string.audio_metadata_mime_type_title),
				text = audio.mimeType
			)
		}
		audio.metaData?.artists?.let {
			item {
				FileMetaData(
					title = stringResource(R.string.audio_metadata_file_artist_name),
					text = it
				)
			}
		}
		audio.metaData?.author?.let {
			item {
				FileMetaData(
					title = stringResource(R.string.audio_metadata_file_author_name),
					text = it
				)
			}
		}
		item {
			FileMetaData(
				title = stringResource(id = R.string.audio_metadata_last_edit_title),
				text = lastModified
			)
		}
		audio.metaData?.let { metaData ->
			item {
				FileMetaData(
					title = stringResource(id = R.string.audio_metadata_channel_title),
					text = stringResource(
						id = if (metaData.channelCount == 1) R.string.audio_metadata_channel_mono
						else R.string.audio_metadata_channel_stereo
					),
					modifier = Modifier.animateItem(),
				)
			}
			item {
				FileMetaData(
					title = stringResource(id = R.string.audio_metadata_bitrate_title),
					text = stringResource(
						id = R.string.audio_metadata_bitrate,
						metaData.bitRateInKbps
					),
					modifier = Modifier.animateItem(),
				)
			}
			item {
				FileMetaData(
					title = stringResource(id = R.string.audio_metadata_sample_rate_title),
					text = stringResource(
						id = R.string.audio_metadata_sample_rate,
						metaData.sampleRateInKHz
					),
					modifier = Modifier.animateItem(),
				)
			}
			metaData.locationString?.let { location ->
				item {
					FileMetaData(
						title = stringResource(R.string.audio_metadata_file_location),
						text = location,
						modifier = Modifier.animateItem()
					)
				}
			}
		}
		audio.path?.let { path ->
			item {
				FileMetaData(
					title = stringResource(id = R.string.audio_metadata_path_title),
					text = path,
					modifier = Modifier.animateItem(),
				)
			}

		}
		if (deviceContext != null) {
			item {
				DeviceContextMetadataSection(
					recordingId = audio.id,
					deviceContext = deviceContext,
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 8.dp)
						.animateItem(),
				)
			}
		}
			if (onOpenChat != null) {
			item {
				OutlinedButton(
					onClick = onOpenChat,
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 8.dp),
				) {
					Text(stringResource(PlayerStrings.string.feature_player_ask_about_this_recording))
				}
			}
		}
	}
}

@Composable
private fun DeviceContextMetadataSection(
	recordingId: Long,
	deviceContext: RecordingDeviceContext,
	modifier: Modifier = Modifier,
) {
	var expanded by rememberSaveable(recordingId) { mutableStateOf(false) }

	val preview = remember(deviceContext) {
		DeviceContextFormatter.formatCompactPreview(deviceContext)
	}
	val locationText = remember(deviceContext.location) {
		DeviceContextFormatter.formatLocationSummary(deviceContext.location)
	}
	val batteryText = remember(deviceContext.batteryStart, deviceContext.batteryEnd) {
		DeviceContextFormatter.formatBatterySummary(deviceContext.batteryStart, deviceContext.batteryEnd)
	}
	val networkText = remember(deviceContext.networkStart) {
		DeviceContextFormatter.formatNetworkSummary(deviceContext.networkStart)
	}
	val storageText = remember(deviceContext.storageStart, deviceContext.storageEnd) {
		DeviceContextFormatter.formatStorageSummary(deviceContext.storageStart, deviceContext.storageEnd)
	}
	val capturedAtText = remember(deviceContext.capturedAtMillis) {
		if (deviceContext.capturedAtMillis > 0L) {
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
				.format(Date(deviceContext.capturedAtMillis))
		} else null
	}

	Card(
		modifier = modifier,
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
		),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.clickable(role = Role.Button) { expanded = !expanded }
				.padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Row(modifier = Modifier.fillMaxWidth()) {
				Text(
					text = (if (expanded) "▾ " else "▸ ") +
						stringResource(PlayerStrings.string.feature_player_device_context_heading),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
			if (!expanded && preview != null) {
				Text(
					text = preview,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			AnimatedVisibility(visible = expanded) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(top = 4.dp),
					verticalArrangement = Arrangement.spacedBy(2.dp),
				) {
					FileMetaData(
						title = stringResource(PlayerStrings.string.feature_player_device_context_location),
						text = locationText ?: stringResource(PlayerStrings.string.feature_player_device_context_location_skipped),
						titleStyle = MaterialTheme.typography.labelMedium,
						textStyle = MaterialTheme.typography.bodyMedium,
					)
					batteryText?.let {
						FileMetaData(
							title = stringResource(PlayerStrings.string.feature_player_device_context_battery),
							text = it,
							titleStyle = MaterialTheme.typography.labelMedium,
							textStyle = MaterialTheme.typography.bodyMedium,
						)
					}
					networkText?.let {
						FileMetaData(
							title = stringResource(PlayerStrings.string.feature_player_device_context_network),
							text = it,
							titleStyle = MaterialTheme.typography.labelMedium,
							textStyle = MaterialTheme.typography.bodyMedium,
						)
					}
					storageText?.let {
						FileMetaData(
							title = stringResource(PlayerStrings.string.feature_player_device_context_storage),
							text = it,
							titleStyle = MaterialTheme.typography.labelMedium,
							textStyle = MaterialTheme.typography.bodyMedium,
						)
					}
					capturedAtText?.let {
						FileMetaData(
							title = stringResource(PlayerStrings.string.feature_player_device_context_captured_at),
							text = it,
							titleStyle = MaterialTheme.typography.labelMedium,
							textStyle = MaterialTheme.typography.bodyMedium,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun FileMetaData(
	title: String,
	text: String,
	modifier: Modifier = Modifier,
	titleStyle: TextStyle = MaterialTheme.typography.labelLarge,
	textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
	titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
	textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp, horizontal = 4.dp)
	) {
		Text(
			text = title,
			style = titleStyle,
			color = titleColor,
		)
		Text(
			text = text,
			style = textStyle,
			color = textColor,
		)
	}
}

@PreviewLightDark
@Composable
private fun FileMetaDataSheetPreview() = RecorderAppTheme {
	Surface(color = MaterialTheme.colorScheme.background) {
		FileMetaDataSheetContent(
			audio = PlayerPreviewFakes.FAKE_AUDIO_MODEL,
			modifier = Modifier.fillMaxWidth()
		)
	}
}
