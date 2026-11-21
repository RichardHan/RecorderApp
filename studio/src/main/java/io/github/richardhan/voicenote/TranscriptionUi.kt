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
internal fun TranscriptionRow(file: File, speech: SpeechConnection) {
	var confirm by remember { mutableStateOf(false) }
	val transcript by produceState("", file, speech.revision) {
		value = withContext(Dispatchers.IO) { transcriptFile(file).takeIf { it.exists() }?.readText().orEmpty() }
	}
	if (transcript.isNotEmpty()) SelectionContainer { Text(transcript) }
	TextButton(onClick = { confirm = true }, enabled = !speech.busy && speech.valid()) { Text(stringResource(R.string.upload)) }
	if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.confirm)) },
		text = { Text(stringResource(R.string.disclose, speech.base, speech.model)) },
		confirmButton = { Button(onClick = { confirm = false; speech.transcribe(file) }) { Text(stringResource(R.string.upload)) } },
		dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
