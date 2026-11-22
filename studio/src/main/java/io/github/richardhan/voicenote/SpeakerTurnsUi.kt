package io.github.richardhan.voicenote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun timestamp(seconds: Double) = String.format(Locale.ROOT, "%d:%02d", seconds.toInt() / 60, seconds.toInt() % 60)

@Composable
private fun SpeakerFilter(ids: Set<Int>, names: Map<Int, String>, selected: Int?, onSelect: (Int?) -> Unit) {
	var expanded by remember { mutableStateOf(false) }
	val all = stringResource(R.string.all_speakers)
	@Composable fun label(id: Int): String {
		val number = stringResource(R.string.speaker_number, id)
		return names[id]?.let { "$it · $number" } ?: number
	}
	Box {
		OutlinedButton(onClick = { expanded = true }) {
			Text(stringResource(R.string.speaker_filter, if (selected == null) all else label(selected)))
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			DropdownMenuItem(text = { Text(all) }, onClick = { expanded = false; onSelect(null) })
			for (id in ids) DropdownMenuItem(text = { Text(label(id)) }, onClick = { expanded = false; onSelect(id) })
		}
	}
}

@Composable
internal fun SpeakerAction(file: File, state: SpeakerAnalysis, transcriptRevision: Int, library: RecordingLibrary) {
	if (file.extension == "youtube") return
	var open by rememberSaveable(file.path) { mutableStateOf(false) }
	TextButton(enabled = library.started == 0L, onClick = {
		library.stopPlayback()
		open = true
	}) { Text(stringResource(R.string.turns)) }
	if (!open) return
	var count by remember { mutableStateOf("") }
	val timeline by produceState<SpeakerTimeline?>(null, file, state.revision) {
		value = withContext(Dispatchers.IO) { readTimeline(file) }
	}
	val turns = timeline?.turns.orEmpty()
	var selectedSpeaker by rememberSaveable(file.path) { mutableStateOf<Int?>(null) }
	val speakerIds = remember(turns) { turns.mapTo(linkedSetOf()) { it.speaker } }
	val filteredIndices = remember(turns, selectedSpeaker) {
		if (selectedSpeaker == null) null else turns.indices.filter { turns[it].speaker == selectedSpeaker }
	}
	val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
		if (uri != null) state.export(file, uri, selectedSpeaker)
	}
	var playing by remember(file, turns) { mutableStateOf<Int?>(null) }
	var failedTurn by remember(file, turns) { mutableStateOf<Int?>(null) }
	playing?.let { index ->
		val turn = turns[index]
		key(index) {
			AudioRangePreview(file, (turn.start * 1000).roundToLong(), (turn.end * 1000).roundToLong(),
				onStop = { playing = null }, onError = { failedTurn = index; playing = null })
		}
	}
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
						onClick = { playing = null; failedTurn = null; selectedSpeaker = null; state.analyze(file, count.toIntOrNull()) }) { Text(stringResource(R.string.turn_analyze)) }
					else Button(onClick = state::download) { Text(stringResource(R.string.turn_download)) }
					if (state.message != 0) Text(stringResource(state.message),
						color = if (state.message == R.string.turn_exported) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
				}
				if (timeline != null && turns.isEmpty()) item { Text(stringResource(R.string.no_speakers)) }
				if (!timeline?.names.isNullOrEmpty()) item { Text(stringResource(R.string.rerun_names)) }
				if (speakerIds.size > 1) item {
					SpeakerFilter(speakerIds, timeline?.names.orEmpty(), selectedSpeaker) { id ->
						if (id != selectedSpeaker) {
							playing = null
							failedTurn = null
							selectedSpeaker = id
						}
					}
				}
				items(filteredIndices?.size ?: turns.size) { position ->
					val index = filteredIndices?.get(position) ?: position
					val turn = turns[index]
					val speaker = timeline?.names?.get(turn.speaker) ?: stringResource(R.string.speaker_number, turn.speaker)
					val range = timestamp(turn.start) + " – " + timestamp(turn.end)
					val active = playing == index
					val playLabel = stringResource(if (active) R.string.stop_preview else R.string.turn_listen)
					val playDescription = stringResource(R.string.turn_play_description, playLabel, speaker, range)
					Row(verticalAlignment = Alignment.CenterVertically) {
						TextButton(modifier = Modifier.weight(1f), enabled = !state.busy, onClick = {
							playing = null
							alias = timeline?.names?.get(turn.speaker).orEmpty()
							edit = turn.speaker
						}) { Text("$speaker · $range") }
						TextButton(
							modifier = Modifier.semantics { contentDescription = playDescription },
							enabled = !state.busy,
							onClick = { failedTurn = null; playing = if (active) null else index }
						) { Text(playLabel) }
					}
					if (failedTurn == index) Text(stringResource(R.string.turn_play_error), color = MaterialTheme.colorScheme.error)
					groups[index]?.forEach { Text(it.text) }
				}
			}
		},
		dismissButton = {
			if (turns.isNotEmpty()) TextButton(enabled = !state.busy, onClick = {
				playing = null
				val suffix = selectedSpeaker?.let { "-speaker-$it" }.orEmpty()
				saveDocument.launch("${file.nameWithoutExtension}-timeline$suffix.txt")
			}) { Text(stringResource(if (selectedSpeaker == null) R.string.turn_export else R.string.export_speaker)) }
		},
		confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } }
	)
}
