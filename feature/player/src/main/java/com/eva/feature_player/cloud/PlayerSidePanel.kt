package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eva.feature_player.R

/**
 * The free panel under the waveform: transcript, chapters, clips, the optional cleaned transcript or decisions. A full player tab would need a layout rewrite, so the
 * views share this slot with a switcher; both keep their own scroll state.
 */
@Composable
fun PlayerSidePanel(
	recordingId: Long,
	transcriptPanel: @Composable (Modifier) -> Unit,
	onSeekTo: (Long) -> Unit,
	modifier: Modifier = Modifier,
	positionMs: () -> Long = { 0L },
	totalMs: () -> Long = { 0L },
	bookmarkTimesMs: List<Long> = emptyList(),
) {
	// 0 = transcript, 1 = chapters, 2 = clips, 3 = cleaned transcript, 4 = decisions
	var tab by rememberSaveable(recordingId) { mutableIntStateOf(0) }
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(StringsRes.string.feature_player_transcript)) })
			FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(stringResource(StringsRes.string.feature_player_chapters)) })
			FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text(stringResource(StringsRes.string.feature_player_clip)) })
			FilterChip(selected = tab == 3, onClick = { tab = 3 }, label = { Text(stringResource(R.string.cleanup_tab)) })
			FilterChip(selected = tab == 4, onClick = { tab = 4 }, label = { Text(stringResource(StringsRes.string.feature_player_decisions)) })
		}
		when (tab) {
			1 -> ChaptersPanel(
				recordingId = recordingId,
				onSeekTo = onSeekTo,
				modifier = Modifier.fillMaxWidth().weight(1f),
			)

			2 -> com.eva.feature_player.clips.ClipsPanel(
				recordingId = recordingId,
				positionMs = positionMs,
				totalMs = totalMs,
				bookmarkTimesMs = bookmarkTimesMs,
				onSeekTo = onSeekTo,
				modifier = Modifier.fillMaxWidth().weight(1f),
			)

			3 -> CleanupPanel(
				recordingId = recordingId,
				onSeekTo = onSeekTo,
				modifier = Modifier.fillMaxWidth().weight(1f),
			)
			4 -> DecisionsPanel(
				recordingId = recordingId,
				onSeekTo = onSeekTo,
				modifier = Modifier.fillMaxWidth().weight(1f),
			)
			else -> transcriptPanel(Modifier.fillMaxWidth().weight(1f))
		}
	}
}
