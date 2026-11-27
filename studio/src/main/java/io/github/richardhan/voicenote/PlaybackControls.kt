package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay

@Composable
internal fun PlaybackControls(library: RecordingLibrary) {
	val position by produceState(0L, library.playingFile, library.playbackPaused, library.seekRevision) {
		do {
			value = library.playbackPosition()
			delay(250)
		} while (!library.playbackPaused)
	}
	var drag by remember { mutableStateOf<Float?>(null) }
	val duration = library.playbackDuration
	library.playingFile?.let { PlaybackWaveform(it, duration, position) }
	Text(formatClipTime(position.toInt()) + " / " + formatClipTime(duration))
	Slider(value = drag ?: position.toFloat().coerceIn(0f, duration.toFloat()), valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
		enabled = duration > 0, onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { library.seekPlayback(it.toLong()) }; drag = null })
	FlowRow {
		TextButton(onClick = { library.pausePlayback(!library.playbackPaused) }) { Text(stringResource(if (library.playbackPaused) R.string.play else R.string.pause_playback)) }
		var open by remember { mutableStateOf(false) }
		Box {
			TextButton(onClick = { open = true }) { Text("${library.playbackSpeed}×") }
			DropdownMenu(open, { open = false }) {
				for (speed in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) DropdownMenuItem(text = { Text("${speed}×") }, onClick = {
					library.setSpeed(speed); open = false
				})
			}
		}
	}
}
