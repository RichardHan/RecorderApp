package com.eva.feature_player.cloud

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp

/**
 * The free panel under the waveform: transcript, chapters or decisions. A full player tab would need a layout rewrite, so the
 * views share this slot with a switcher; both keep their own scroll state.
 */
@Composable
fun PlayerSidePanel(
	recordingId: Long,
	transcriptPanel: @Composable (Modifier) -> Unit,
	onSeekTo: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	// 0 = transcript, 1 = chapters, 2 = decisions
	var tab by rememberSaveable(recordingId) { mutableIntStateOf(0) }
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("逐字稿") })
			FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("章節") })
			FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("決議") })
		}
		when (tab) {
			1 -> ChaptersPanel(
				recordingId = recordingId,
				onSeekTo = onSeekTo,
				modifier = Modifier.fillMaxWidth().weight(1f),
			)
			2 -> DecisionsPanel(
				recordingId = recordingId,
				onSeekTo = onSeekTo,
				modifier = Modifier.fillMaxWidth().weight(1f),
			)
			else -> transcriptPanel(Modifier.fillMaxWidth().weight(1f))
		}
	}
}
