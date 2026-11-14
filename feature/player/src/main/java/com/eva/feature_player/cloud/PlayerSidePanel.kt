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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The free panel under the waveform: transcript or chapters. A full player tab would need a layout rewrite, so the two
 * views share this slot with a switcher; both keep their own scroll state.
 */
@Composable
fun PlayerSidePanel(
	recordingId: Long,
	transcriptPanel: @Composable (Modifier) -> Unit,
	onSeekTo: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	var showChapters by rememberSaveable(recordingId) { mutableStateOf(false) }
	Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			FilterChip(selected = !showChapters, onClick = { showChapters = false }, label = { Text("逐字稿") })
			FilterChip(selected = showChapters, onClick = { showChapters = true }, label = { Text("章節") })
		}
		if (showChapters) {
			ChaptersPanel(
				recordingId = recordingId,
				onSeekTo = onSeekTo,
				modifier = Modifier.fillMaxWidth().weight(1f),
			)
		} else {
			transcriptPanel(Modifier.fillMaxWidth().weight(1f))
		}
	}
}
