@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.eva.feature_recorder.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.datasource.CollectionPreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.eva.datastore.domain.models.TranscriptionSettings
import com.eva.feature_recorder.util.RecorderPreviewFakes
import com.eva.recorder.domain.recorder.LiveTranscriptState
import com.eva.recorder.domain.recorder.LiveTranscriptStatus
import com.eva.recorder.domain.models.RecorderAction
import com.eva.recorder.domain.models.RecorderState
import com.eva.recorder.utils.DeferredRecordedPointList
import com.eva.ui.R
import com.eva.ui.theme.DownloadableFonts
import com.eva.ui.theme.RecorderAppTheme
import kotlinx.datetime.LocalTime

@Composable
internal fun RecorderContent(
	timer: () -> LocalTime,
	recordingPointsCallback: DeferredRecordedPointList,
	recorderState: RecorderState,
	onRecorderAction: (RecorderAction) -> Unit,
	modifier: Modifier = Modifier,
	transcriptions: LiveTranscriptState = LiveTranscriptState(),
	liveCloud: com.eva.cloudtranscribe.live.LiveCloudState = com.eva.cloudtranscribe.live.LiveCloudState(),
	isStartRecordingEnabled: Boolean = true,
	transcriptionSettings: TranscriptionSettings = TranscriptionSettings(),
	onNavigateToSettings: () -> Unit = {},
	onToggleLiveTranscription: (Boolean) -> Unit = {},
	slackStatus: com.eva.cloudtranscribe.slack.SlackSyncStatus = com.eva.cloudtranscribe.slack.SlackSyncStatus(),
	onToggleSlackSync: (Boolean) -> Unit = {},
	/** Off in previews/tests (needs Hilt). */
	showNotes: Boolean = false,
) {
	Column(modifier = modifier.fillMaxSize()) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.padding(horizontal = 4.dp),
		) {
			androidx.compose.foundation.layout.FlowRow(
				horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
				verticalArrangement = Arrangement.Center,
				itemVerticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.fillMaxWidth(),
			) {
				RecorderTimerText(
					time = timer,
					fontFamily = DownloadableFonts.SPLINE_SANS_MONO_FONT_FAMILY,
					style = MaterialTheme.typography.titleLarge,
				)
				AssociatedRecorderActions(
					transcriptionSettings = transcriptionSettings,
					onOpenSettings = onNavigateToSettings,
					onToggleLiveTranscription = onToggleLiveTranscription,
				)
				if (showNotes) RecordingNotesChip()
				if (showNotes)
					com.eva.feature_recorder.photos.RecordingPhotosButton(timeMs = { timer().toMillisecondOfDay().toLong() })
				SlackSyncIndicator(
					status = slackStatus,
					isRecording = recorderState == RecorderState.RECORDING || recorderState == RecorderState.PAUSED,
					onToggle = onToggleSlackSync,
				)
			}
			RecorderInputLevelBar(
				points = recordingPointsCallback,
				modifier = Modifier.fillMaxWidth(),
			)
			if (liveCloud.active) {
				LiveTranscriptPanes(
					offline = transcriptions,
					cloud = liveCloud,
					isActive = recorderState.canReadAmplitudes,
					showOffline = transcriptionSettings.isEnabled || transcriptions.hasText,
					fontFamily = DownloadableFonts.SOURCE_SANS_3_FONT_FAMILY,
					modifier = Modifier.fillMaxWidth().weight(1f),
				)
			} else if (transcriptionSettings.isEnabled || transcriptions.hasText) {
				RecorderLiveTranscriptionText(
					result = transcriptions,
					isActive = recorderState.canReadAmplitudes,
					fontFamily = DownloadableFonts.SOURCE_SANS_3_FONT_FAMILY,
					modifier = Modifier
						.fillMaxWidth()
						.weight(1f)
				)
			}
		}
		androidx.compose.material3.Surface(tonalElevation = 2.dp, shadowElevation = 4.dp) {
			AnimatedRecorderActionTray(
				recorderState = recorderState,
				onRecorderAction = onRecorderAction,
				isStartRecordingEnabled = isStartRecordingEnabled,
				onAddBookMark = { onRecorderAction(RecorderAction.AddBookMarkAction) },
				modifier = Modifier
					.fillMaxWidth()
					.navigationBarsPadding()
					.padding(vertical = 8.dp),
			)
		}
	}
}

private class RecorderContentRecorderStatePreviewParams :
	CollectionPreviewParameterProvider<RecorderState>(
		listOf(
			RecorderState.RECORDING,
			RecorderState.COMPLETED,
			RecorderState.PAUSED
		)
	)

@PreviewLightDark
@Composable
private fun RecorderContentPreview(
	@PreviewParameter(RecorderContentRecorderStatePreviewParams::class)
	recorderState: RecorderState,
) = RecorderAppTheme {
	Surface {
		RecorderContent(
			timer = { LocalTime(0, 10, 56, 0) },
			recorderState = recorderState,
			recordingPointsCallback = { RecorderPreviewFakes.PREVIEW_RECORDER_AMPLITUDE_FLOAT_ARRAY_LARGE },
			onRecorderAction = {},
			transcriptionSettings = TranscriptionSettings(isEnabled = false),
			modifier = Modifier
				.padding(horizontal = dimensionResource(R.dimen.sc_padding))
				.fillMaxSize()
		)
	}
}

@PreviewLightDark
@Composable
private fun RecorderContentWithTranscriptionsPreview() = RecorderAppTheme {
	Surface {
		RecorderContent(
			timer = { LocalTime(0, 10, 56, 0) },
			recorderState = RecorderState.RECORDING,
			recordingPointsCallback = { RecorderPreviewFakes.PREVIEW_RECORDER_AMPLITUDE_FLOAT_ARRAY_LARGE },
			onRecorderAction = {},
			transcriptionSettings = TranscriptionSettings(isEnabled = true),
			transcriptions = LiveTranscriptState(finalized = listOf("第一句已定稿的字幕", "第二句已定稿的字幕"), status = LiveTranscriptStatus.LISTENING),
			modifier = Modifier
				.padding(horizontal = dimensionResource(R.dimen.sc_padding))
				.fillMaxSize()
		)
	}
}