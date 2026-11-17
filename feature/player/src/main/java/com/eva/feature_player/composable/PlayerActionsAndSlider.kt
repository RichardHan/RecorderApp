package com.eva.feature_player.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.eva.feature_player.state.PlayerEvents
import com.eva.player.domain.model.PlayerMetaData
import com.eva.player.domain.model.PlayerTrackData
import com.eva.player_shared.composables.PlayerTrackSlider2
import com.eva.ui.theme.DownloadableFonts
import com.eva.ui.theme.RecorderAppTheme
import kotlin.time.Duration.Companion.minutes

@Composable
internal fun PlayerActionsAndSlider(
	metaData: PlayerMetaData,
	trackData: () -> PlayerTrackData,
	onPlayerAction: (PlayerEvents) -> Unit,
	modifier: Modifier = Modifier,
	isControllerSet: Boolean = true,
	isPlayerPlaying: Boolean = true,
	bookmarkTimesMs: List<Long> = emptyList(),
	containerShape: Shape = MaterialTheme.shapes.large,
	containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
	contentColor: Color = contentColorFor(backgroundColor = containerColor),
) {
	val played by remember { derivedStateOf { trackData().current.inWholeSeconds } }
	val total by remember { derivedStateOf { trackData().total.inWholeSeconds } }
	val playedText = fmtSec(played)
	val totalText = fmtSec(total)

	Column(
		modifier = modifier,
		verticalArrangement = Arrangement.spacedBy(0.dp)
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier.semantics(mergeDescendants = true) {
				contentDescription = "$playedText / $totalText"
			},
		) {
			Text(
				text = playedText,
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary,
				fontFamily = DownloadableFonts.SPLINE_SANS_MONO_FONT_FAMILY,
			)
			PlayerTrackSlider2(
				trackData = trackData,
				onSeek = { amount -> onPlayerAction(PlayerEvents.OnSeekingPlayer(amount)) },
				onSeekEnd = { onPlayerAction(PlayerEvents.OnSeekEndPlayer) },
				enabled = isControllerSet,
				bookmarkTimesMs = bookmarkTimesMs,
				modifier = Modifier.weight(1f),
			)
			Text(
				text = totalText,
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				fontFamily = DownloadableFonts.SPLINE_SANS_MONO_FONT_FAMILY,
			)
		}
		AudioPlayerActions(
			playerMetaData = metaData,
			isControllerReady = isControllerSet,
			isPlayerPlaying = isPlayerPlaying,
			onPlay = { onPlayerAction(PlayerEvents.OnStartPlayer) },
			onPause = { onPlayerAction(PlayerEvents.OnPausePlayer) },
			onMuteStream = { onPlayerAction(PlayerEvents.OnMutePlayer) },
			onRepeatModeChange = { onPlayerAction(PlayerEvents.OnRepeatModeChange(it)) },
			onRewind = { onPlayerAction(PlayerEvents.OnRewindByNDuration()) },
			onForward = { onPlayerAction(PlayerEvents.OnForwardByNDuration()) },
			onSpeedSelected = { onPlayerAction(PlayerEvents.OnPlayerSpeedChange(it)) },
			color = containerColor,
			shape = containerShape,
			contentColor = contentColor,
		)
	}
}

@PreviewLightDark
@Composable
private fun PlayerActionsAndSliderPreview() = RecorderAppTheme {
	Surface {
		PlayerActionsAndSlider(
			metaData = PlayerMetaData(),
			trackData = { PlayerTrackData(total = 2.minutes) },
			isPlayerPlaying = true,
			onPlayerAction = {},
			bookmarkTimesMs = listOf(20_000L, 80_000L),
			modifier = Modifier.padding(12.dp)
		)
	}
}
