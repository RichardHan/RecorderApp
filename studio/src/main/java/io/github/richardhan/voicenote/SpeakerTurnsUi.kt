package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun timestamp(seconds: Double) = String.format(Locale.ROOT, "%d:%02d", seconds.toInt() / 60, seconds.toInt() % 60)

@Composable
internal fun SpeakerAction(file: File, state: SpeakerAnalysis, transcriptRevision: Int) {
	if (file.extension == "youtube") return
	var open by remember { mutableStateOf(false) }
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.turns)) }
	if (!open) return
	var count by remember { mutableStateOf("") }
	val timeline by produceState<SpeakerTimeline?>(null, file, state.revision) {
		value = withContext(Dispatchers.IO) { readTimeline(file) }
	}
	val turns = timeline?.turns.orEmpty()
	var edit by remember { mutableStateOf<Int?>(null) }
	var alias by remember { mutableStateOf("") }
	edit?.let { speaker ->
		AlertDialog(onDismissRequest = { edit = null }, title = { Text(stringResource(R.string.speaker_number, speaker)) },
			text = { OutlinedTextField(alias, { alias = it }, label = { Text(stringResource(R.string.speaker_alias)) }, singleLine = true) },
			confirmButton = { TextButton(onClick = { state.rename(file, speaker, alias); edit = null }) { Text(stringResource(R.string.done)) } },
			dismissButton = { TextButton(onClick = { edit = null }) { Text(stringResource(R.string.cancel)) } })
		return
	}
	val segments by produceState(emptyList(), file, transcriptRevision) { value = withContext(Dispatchers.IO) { readTiming(file) } }
	val groups = remember(turns, segments) { segments.groupBy { closestTurn(turns, it) } }
	AlertDialog(
		onDismissRequest = { open = false },
		title = { Text(stringResource(R.string.turns)) },
		text = {
			LazyColumn(Modifier.heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				item { Text(stringResource(R.string.turn_hint)) }
				if (state.installed) item {
					OutlinedTextField(value = count, onValueChange = { count = it }, singleLine = true,
						label = { Text(stringResource(R.string.turn_count)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
				}
				item {
					if (state.busy) LinearProgressIndicator()
					else if (state.installed) Button(enabled = count.isBlank() || (count.toIntOrNull() ?: 0) in 1..100,
						onClick = { state.analyze(file, count.toIntOrNull()) }) { Text(stringResource(R.string.turn_analyze)) }
					else Button(onClick = state::download) { Text(stringResource(R.string.turn_download)) }
					if (state.message != 0) Text(stringResource(state.message), color = MaterialTheme.colorScheme.error)
				}
				if (timeline != null && turns.isEmpty()) item { Text(stringResource(R.string.no_speakers)) }
				if (!timeline?.names.isNullOrEmpty()) item { Text(stringResource(R.string.rerun_names)) }
				itemsIndexed(turns) { index, turn ->
					TextButton(enabled = !state.busy, onClick = {
						alias = timeline?.names?.get(turn.speaker).orEmpty()
						edit = turn.speaker
					}) {
						Text((timeline?.names?.get(turn.speaker) ?: stringResource(R.string.speaker_number, turn.speaker)) +
							" · " + timestamp(turn.start) + " – " + timestamp(turn.end))
					}
					groups[index]?.forEach { Text(it.text) }
				}
			}
		}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } }
	)
}
