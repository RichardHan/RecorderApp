package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource

@Composable
internal fun LiveMeeting(library: RecordingLibrary, speech: SpeechConnection) {
	var edit by rememberSaveable { mutableStateOf(false) }
	var confirm by rememberSaveable { mutableStateOf(false) }
	val recording = library.captureTarget
	if (recording == null || library.started != 0L) {
		TextButton(onClick = { edit = true }) { Text(stringResource(R.string.meeting_agenda)) }
	}
	if (edit) {
		var draft by rememberSaveable { mutableStateOf(library.agenda) }
		AlertDialog(onDismissRequest = { edit = false }, title = { Text(stringResource(R.string.meeting_agenda)) },
			text = { OutlinedTextField(draft, { draft = it }) },
			confirmButton = { TextButton(onClick = { library.updateAgenda(draft); edit = false }) { Text(stringResource(R.string.done)) } },
			dismissButton = { TextButton(onClick = { edit = false }) { Text(stringResource(R.string.cancel)) } })
	}
	if (recording == null || !library.supportsHighlights) return
	if (library.highlights.isNotBlank()) Text(library.highlights)
	if (library.highlightsEnabled) TextButton(onClick = library::stopHighlights) { Text(stringResource(R.string.meeting_stop_highlights)) }
	else TextButton(enabled = speech.canAnalyze() && speech.hasSavedConnection && speech.profileId() == speech.savedProfile, onClick = { confirm = true }) {
		Text(stringResource(R.string.meeting_live_highlights))
	}
	if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.meeting_live_highlights)) },
		text = { Text(stringResource(R.string.meeting_highlights_disclose, speech.base, speech.chatModel)) },
		confirmButton = { TextButton(onClick = { confirm = false; library.startHighlights(speech.savedProfile) }) { Text(stringResource(R.string.meeting_live_highlights)) } },
		dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
