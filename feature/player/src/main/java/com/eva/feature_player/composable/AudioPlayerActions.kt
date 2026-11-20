package com.eva.feature_player.composable

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.eva.player.domain.model.PlayerMetaData
import com.eva.player.domain.model.PlayerPlayBackSpeed
import com.eva.player.domain.model.PlayerState
import com.eva.player_shared.composables.AnimatedPlayPauseButton
import com.eva.ui.R
import com.eva.ui.theme.RecorderAppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioPlayerActions(
	playerMetaData: PlayerMetaData,
	onPause: () -> Unit,
	onPlay: () -> Unit,
	modifier: Modifier = Modifier,
	onRepeatModeChange: (Boolean) -> Unit = {},
	onMuteStream: () -> Unit = {},
	onForward: () -> Unit = {},
	onRewind: () -> Unit = {},
	isControllerReady: Boolean = true,
	isPlayerPlaying: Boolean = true,
	onSpeedSelected: (PlayerPlayBackSpeed) -> Unit = {},
	shape: Shape = MaterialTheme.shapes.large,
	color: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
	contentColor: Color = contentColorFor(backgroundColor = color),
) {
	val playBackSpeedBottomSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val scope = rememberCoroutineScope()

	var openPlayBackSpeedBottomSheet by remember { mutableStateOf(false) }

	if (openPlayBackSpeedBottomSheet) {
		ModalBottomSheet(
			sheetState = playBackSpeedBottomSheet,
			onDismissRequest = { openPlayBackSpeedBottomSheet = false },
		) {
			PlayBackSpeedSelector(
				selectedSpeed = playerMetaData.playBackSpeed,
				onSpeedSelected = onSpeedSelected,
				contentPadding = PaddingValues(all = dimensionResource(id = R.dimen.bottom_sheet_padding_lg))
			)
		}
	}

	Surface(
		modifier = modifier,
		shape = shape,
		color = color,
		contentColor = contentColor,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 2.dp, vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			CompactIconButton(onClick = onMuteStream, enabled = isControllerReady) {
				Icon(
					painter = painterResource(id = R.drawable.ic_mute_stream),
					contentDescription = stringResource(id = R.string.player_action_mute),
					tint = if (playerMetaData.isMuted) MaterialTheme.colorScheme.primary
					else contentColor,
				)
			}
			CompactIconButton(
				onClick = { onRepeatModeChange(!playerMetaData.isRepeating) },
				enabled = isControllerReady,
			) {
				Icon(
					painter = painterResource(id = R.drawable.ic_repeat),
					contentDescription = stringResource(id = R.string.player_action_repeat),
					tint = if (playerMetaData.isRepeating) MaterialTheme.colorScheme.primary
					else contentColor,
				)
			}
			CompactIconButton(
				onClick = {
					scope.launch { playBackSpeedBottomSheet.show() }
						.invokeOnCompletion { openPlayBackSpeedBottomSheet = true }
				},
				enabled = isControllerReady,
			) {
				Text(
					text = stringResource(
						R.string.player_playback_speed,
						playerMetaData.playBackSpeed.speed
					),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
			androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
			CompactIconButton(onClick = onRewind, enabled = isControllerReady) {
				Icon(
					painter = painterResource(id = R.drawable.ic_fast_rewind),
					contentDescription = stringResource(id = R.string.player_fast_rewind),
				)
			}
			AnimatedPlayPauseButton(
				isPlaying = isPlayerPlaying,
				enabled = isControllerReady,
				onPause = onPause,
				onPlay = onPlay,
			)
			CompactIconButton(onClick = onForward, enabled = isControllerReady) {
				Icon(
					painter = painterResource(id = R.drawable.ic_fast_forward),
					contentDescription = stringResource(id = R.string.player_fast_forward),
				)
			}
		}
	}
}


@Composable
private fun CompactIconButton(
	onClick: () -> Unit,
	enabled: Boolean,
	content: @Composable () -> Unit,
) {
	IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
		content()
	}
}

@PreviewLightDark
@Composable
private fun AudioPlayerActionsPreview() = RecorderAppTheme {
	AudioPlayerActions(
		playerMetaData = PlayerMetaData(playerState = PlayerState.PLAYER_READY),
		onPlay = {},
		onPause = {},
	)
}
