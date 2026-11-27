package io.github.richardhan.voicenote

import android.media.MediaRecorder.AudioEncoder
import android.media.MediaRecorder.OutputFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource

internal enum class CaptureFormat(val label: String, val ext: String, val container: Int, val encoder: Int, val rate: Int, val bitrates: List<Int>) {
	AAC("AAC", "m4a", OutputFormat.MPEG_4, AudioEncoder.AAC, 44100, listOf(64000, 128000, 192000)),
	OPUS("Opus", "ogg", OutputFormat.OGG, AudioEncoder.OPUS, 48000, listOf(24000, 48000, 96000)),
	NB("AMR-NB", "amr", OutputFormat.AMR_NB, AudioEncoder.AMR_NB, 8000, listOf(4750, 7950, 12200)),
	WB("AMR-WB", "awb", OutputFormat.AMR_WB, AudioEncoder.AMR_WB, 16000, listOf(6600, 15850, 23850))
}

@Composable
internal fun CaptureSettings(library: RecordingLibrary, modelInstalled: Boolean) {
	var formatMenu by remember { mutableStateOf(false) }
	var rateMenu by remember { mutableStateOf(false) }
	if (modelInstalled) FilterChip(selected = library.liveEnabled, onClick = { library.enableLive(!library.liveEnabled) }, label = { Text(stringResource(R.string.live_asr)) })
	if (!modelInstalled || !library.liveEnabled) FlowRow {
		Box {
			FilledTonalButton(onClick = { formatMenu = true }) { Text(library.format.label) }
			DropdownMenu(formatMenu, { formatMenu = false }) {
				CaptureFormat.entries.forEach { format ->
					DropdownMenuItem(text = { Text(format.label) }, onClick = { library.configure(format, library.quality); formatMenu = false })
				}
			}
		}
		Box {
			FilledTonalButton(onClick = { rateMenu = true }) { Text(stringResource(R.string.bitrate, library.format.bitrates[library.quality] / 1000.0)) }
			DropdownMenu(rateMenu, { rateMenu = false }) {
				library.format.bitrates.forEachIndexed { index, bitrate ->
					DropdownMenuItem(text = { Text(stringResource(R.string.bitrate, bitrate / 1000.0)) }, onClick = { library.configure(library.format, index); rateMenu = false })
				}
			}
		}
	}
	RecordingContextOptions(library)
}
