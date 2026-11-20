package com.eva.player_shared.composables

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.eva.player.domain.model.PlayerTrackData
import com.eva.player_shared.util.BookmarkTickPositions
import com.eva.ui.theme.RecorderAppTheme
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerTrackSlider2(
	trackData: () -> PlayerTrackData,
	onSeek: (Duration) -> Unit,
	modifier: Modifier = Modifier,
	onSeekEnd: () -> Unit = {},
	enabled: Boolean = true,
	bookmarkTimesMs: List<Long> = emptyList(),
	interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
	val state = remember {
		SliderState(
			value = trackData().playRatio,
			valueRange = 0f..1f,
			onValueChangeFinished = onSeekEnd,
		).also { state ->
			// on value change will be called only when the value changed completed
			// via interactions not update
			state.onValueChange = { value ->
				val seekAmount = trackData().calculateSeekAmount(value)
				onSeek(seekAmount)
			}
		}
	}

	// update the track ratio when changed
	LaunchedEffect(state) {
		snapshotFlow { trackData().playRatio }
			.onEach { value -> state.value = value }
			.launchIn(this)
	}

	val tickRatios = remember(bookmarkTimesMs, trackData().total) {
		BookmarkTickPositions.ratios(
			positionsMs = bookmarkTimesMs,
			totalMs = trackData().total.inWholeMilliseconds,
		)
	}
	val tickColor = MaterialTheme.colorScheme.tertiary

	Box(modifier = modifier) {
		Slider(
			state = state,
			enabled = enabled,
			colors = SliderDefaults.colors(
				activeTrackColor = MaterialTheme.colorScheme.primary,
				thumbColor = MaterialTheme.colorScheme.primary,
				inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
			),
			modifier = Modifier.fillMaxWidth(),
			interactionSource = interactionSource,
		)
		if (tickRatios.isNotEmpty()) {
			BookmarkTicks(
				ratios = tickRatios,
				color = tickColor,
				modifier = Modifier
					.matchParentSize()
					.padding(horizontal = 10.dp),
			)
		}
	}
}

@Composable
private fun BookmarkTicks(
	ratios: List<Float>,
	color: Color,
	modifier: Modifier = Modifier,
) {
	Canvas(modifier = modifier) {
		val stroke = 2.dp.toPx()
		val top = size.height * 0.22f
		val bottom = size.height * 0.78f
		ratios.forEach { ratio ->
			val x = size.width * ratio
			drawLine(
				color = color,
				start = Offset(x, top),
				end = Offset(x, bottom),
				strokeWidth = stroke,
				cap = StrokeCap.Round,
			)
		}
	}
}

@Preview
@Composable
private fun PlayerTrackSlider2Preview() = RecorderAppTheme {
	var trackState by remember { mutableStateOf(PlayerTrackData(0.seconds, 10.seconds)) }

	PlayerTrackSlider2(
		trackData = { trackState },
		onSeek = { trackState = trackState.copy(current = it) },
		bookmarkTimesMs = listOf(2_000L, 7_000L),
	)
}
