package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ConnectionPanel(speech: SpeechConnection) {
	var editing by remember { mutableStateOf(false) }
	var menu by remember { mutableStateOf(false) }
	OutlinedButton(onClick = { editing = true }, enabled = !speech.busy) {
		Text(stringResource(R.string.configure) + " · " + speech.model.ifBlank { stringResource(R.string.custom) })
	}
	if (speech.error != 0) Text(
		if (speech.error == R.string.http_error) stringResource(speech.error, speech.httpStatus) else stringResource(speech.error),
		color = MaterialTheme.colorScheme.error
	)
	if (!editing) return
	AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.configure)) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(if (speech.custom) stringResource(R.string.custom) else stringResource(R.string.standard))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Switch(checked = speech.custom, onCheckedChange = { speech.setCustomMode(it) }, enabled = !speech.busy)
					Text(stringResource(R.string.custom))
				}
				if (speech.custom) OutlinedTextField(speech.base, speech::changeBase, singleLine = true,
					label = { Text(stringResource(R.string.base)) })
				OutlinedTextField(speech.key, { speech.key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
					label = { Text(stringResource(R.string.key)) })
				OutlinedTextField(speech.model, { speech.model = it }, singleLine = true, label = { Text(stringResource(R.string.model_id)) })
				if (!speech.custom) {
					Box {
						TextButton(onClick = { speech.loadModels(); menu = true }, enabled = !speech.busy) { Text(stringResource(R.string.refresh)) }
						DropdownMenu(expanded = menu && !speech.busy && speech.models.isNotEmpty(), onDismissRequest = { menu = false }) {
							speech.models.forEach { id ->
								DropdownMenuItem(text = { Text(id) }, onClick = { speech.model = id; menu = false })
							}
						}
					}
				}
				if (speech.error != 0) Text(if (speech.error == R.string.http_error) stringResource(speech.error, speech.httpStatus) else stringResource(speech.error))
			}
		}, confirmButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.done)) } })
}

@Composable
internal fun TranscriptionRow(file: File, recordedAt: String, speech: SpeechConnection, local: LocalSummary) {
	var confirm by remember { mutableStateOf(false) }
	val video = file.extension == "youtube"
	val link = if (video) produceState("", file) {
		value = withContext(Dispatchers.IO) {
			try { file.readText() } catch (_: IOException) { "" }
		}
	}.value else ""
	if (link.isNotEmpty()) Text(link)
	val saved by produceState(SavedTranscript(""), file, speech.revision) {
		value = withContext(Dispatchers.IO) { loadSavedTranscript(file) }
	}
	if (saved.text.isNotEmpty()) {
		saved.receipt?.let { receipt ->
			Text(stringResource(R.string.receipt_model, receipt.model), style = MaterialTheme.typography.labelLarge)
			val fee = receipt.costUsd?.let { stringResource(R.string.receipt_fee, it) } ?: stringResource(R.string.receipt_no_fee)
			Text(stringResource(R.string.receipt_time, receipt.elapsedMs / 1000.0) + " · " + fee,
				style = MaterialTheme.typography.bodySmall)
		}
		SelectionContainer { Text(saved.text) }
		key(saved.text) { SummaryAction(local, file, saved.text) }
	}
	val canSend = !speech.busy && if (video) !speech.custom && speech.key.isNotBlank() && link.isNotBlank() else speech.valid()
	TextButton(onClick = { confirm = true }, enabled = canSend) { Text(stringResource(R.string.upload)) }
	if (!confirm) return
	var videoModel by remember { mutableStateOf("google/gemini-2.5-flash") }
	var timestamps by remember { mutableStateOf(false) }
	AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(if (video) R.string.confirm_video else R.string.confirm)) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(recordedAt, style = MaterialTheme.typography.titleMedium)
				if (video) {
					Text(link)
					Text(stringResource(R.string.video_disclose))
					OutlinedTextField(videoModel, { videoModel = it }, singleLine = true, label = { Text(stringResource(R.string.video_model)) })
				} else {
					Text(stringResource(R.string.disclose, speech.base, speech.model))
					Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
						Checkbox(checked = timestamps, onCheckedChange = { timestamps = it })
						Text(stringResource(R.string.turn_timing))
					}
				}
			}
		},
		confirmButton = {
			Button(enabled = canSend && (!video || videoModel.isNotBlank()), onClick = {
				confirm = false
				if (video) speech.youtube(file, link, videoModel.trim()) else speech.transcribe(file, timestamps)
			}) { Text(stringResource(R.string.upload)) }
		},
		dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
