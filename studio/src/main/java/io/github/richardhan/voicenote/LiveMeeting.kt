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
	if (recording == null) {
		FilterChip(selected = library.liveReady, onClick = { library.readyForLive(!library.liveReady) }, label = { Text(stringResource(R.string.ai_ready)) })
		return
	}
	if (!library.aiCapture) {
		Text(stringResource(R.string.ai_next_recording))
		return
	}
	val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as StudioApplication
	var approveCloud by remember { mutableStateOf(false) }
	var approveSlack by remember { mutableStateOf(false) }
	FlowRow {
		if (app.offline.installed) FilterChip(selected = library.localCaptions, onClick = { library.setLocalCaptions(!library.localCaptions) }, label = { Text(stringResource(R.string.live_asr)) })
		if (library.allowRemote && speech.valid() && speech.protocol != SpeechProtocol.Multipart && speech.hasSavedConnection && speech.savedProfile == speech.profileId()) {
			FilterChip(selected = library.cloudCaptions, onClick = { if (library.cloudCaptions) library.setCloudCaptions(false, "") else approveCloud = true }, label = { Text(stringResource(R.string.live_cloud)) })
		}
		if (library.allowRemote && app.slackPrefs().contains("generation")) FilterChip(selected = library.sessionSlack, onClick = { if (library.sessionSlack) library.setSessionSlack(false) else approveSlack = true }, label = { Text("Slack") })
	}
	if (library.localCaptions && library.localCaption.isNotBlank()) Text(library.localCaption)
	if (approveCloud || approveSlack) AlertDialog(onDismissRequest = { approveCloud = false; approveSlack = false }, text = {
		Text(if (approveCloud) stringResource(R.string.live_cloud_confirm, speech.base, speech.model) else stringResource(R.string.slack_session))
		if (approveCloud && speech.correctLive) Text(stringResource(R.string.ai_correction_destination, speech.textDestination))
	}, confirmButton = { TextButton(onClick = {
		if (approveCloud) library.setCloudCaptions(true, speech.savedProfile) else library.setSessionSlack(true)
		approveCloud = false; approveSlack = false
	}) { Text(stringResource(R.string.slack_allow)) } }, dismissButton = { TextButton(onClick = { approveCloud = false; approveSlack = false }) { Text(stringResource(R.string.cancel)) } })
	if (!library.supportsHighlights) return
	if (library.highlights.isNotBlank()) Text(library.highlights)
	if (library.highlightsEnabled) TextButton(onClick = library::stopHighlights) { Text(stringResource(R.string.meeting_stop_highlights)) }
	else TextButton(enabled = speech.canAnalyze() && speech.hasSavedConnection && speech.profileId() == speech.savedProfile, onClick = { confirm = true }) {
		Text(stringResource(R.string.meeting_live_highlights))
	}
	if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.meeting_live_highlights)) },
		text = { Text(stringResource(R.string.meeting_highlights_disclose, speech.textDestination, speech.chatModel)) },
		confirmButton = { TextButton(onClick = { confirm = false; library.startHighlights(speech.savedProfile) }) { Text(stringResource(R.string.meeting_live_highlights)) } },
		dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
