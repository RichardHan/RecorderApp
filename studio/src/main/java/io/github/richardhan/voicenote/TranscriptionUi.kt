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
				FlowRow {
					for (mode in SpeechProtocol.entries.filter { speech.custom || it != SpeechProtocol.Gemini }) {
						FilterChip(selected = speech.protocol == mode, onClick = { speech.protocol = mode }, label = { Text(mode.label) })
					}
				}
				if (speech.custom) OutlinedTextField(speech.base, speech::changeBase, singleLine = true,
					label = { Text(stringResource(R.string.base)) })
				OutlinedTextField(speech.key, { speech.key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
					label = { Text(stringResource(R.string.key)) })
				OutlinedTextField(speech.model, { speech.model = it }, singleLine = true, label = { Text(stringResource(R.string.model_id)) })
				if (speech.protocol != SpeechProtocol.Multipart) {
					Text(stringResource(R.string.segment_length, speech.segmentSeconds))
					FlowRow { for (seconds in listOf(20, 60, 120, 300)) TextButton(onClick = { speech.segmentSeconds = seconds }) { Text("${seconds}s") } }
					Text(stringResource(R.string.parallel_requests, speech.concurrentRequests))
					FlowRow { for (count in 1..6) TextButton(onClick = { speech.concurrentRequests = count }) { Text(count.toString()) } }
					FilterChip(selected = speech.skipSilence, onClick = { speech.skipSilence = !speech.skipSilence }, label = { Text(stringResource(R.string.skip_silence)) })
				}
				OutlinedTextField(speech.vocabulary, { speech.vocabulary = it }, label = { Text(stringResource(R.string.vocabulary)) })
				if (speech.protocol == SpeechProtocol.Chat) FlowRow {
					for (id in listOf("gemini-4-hydrogen", "gemini-3.8-flash", "gemini-3.5-transcribe")) {
						TextButton(onClick = { speech.model = id }) { Text(id) }
					}
				}
				OutlinedTextField(speech.chatModel, { speech.chatModel = it }, singleLine = true, label = { Text(stringResource(R.string.chat_model)) })
				FlowRow {
					for (id in if (speech.custom) listOf("gemini-4-hydrogen", "gemini-3.8-flash") else listOf("google/gemini-2.5-flash")) {
						TextButton(onClick = { speech.chatModel = id }) { Text(id) }
					}
				}
				if (speech.protocol != SpeechProtocol.Multipart) FilterChip(selected = speech.speakerLabels, onClick = { speech.speakerLabels = !speech.speakerLabels }, label = { Text(stringResource(R.string.meeting_speaker_labels)) })
				if (speech.protocol != SpeechProtocol.Gemini) {
					Text(stringResource(R.string.meeting_reasoning))
					FlowRow {
						for (level in listOf("", "low", "medium", "high")) FilterChip(selected = speech.effort == level,
							onClick = { speech.effort = level }, label = { Text(level.ifEmpty { stringResource(R.string.meeting_off) }) })
					}

					val off = stringResource(R.string.meeting_off)
					Text(stringResource(R.string.meeting_effective_effort, speech.effectiveEffort(speech.model, true) ?: off, speech.effectiveEffort(speech.chatModel) ?: off))
				}
				var inputRate by remember(speech.base, speech.chatModel) { mutableStateOf(speech.chatPrice()) }
				if (speech.chatModel.isNotBlank()) OutlinedTextField(inputRate, {
					inputRate = it
					if (it.isBlank() || reportedCostUsd(it) != null) speech.setChatPrice(it)
				}, isError = inputRate.isNotBlank() && reportedCostUsd(inputRate) == null,
					label = { Text(stringResource(R.string.meeting_model_price)) }, singleLine = true)

				TextButton(enabled = !speech.busy && speech.valid(), onClick = { speech.saveConnection() }) { Text(stringResource(R.string.save_connection)) }
				AutoPanel(speech)
				if (speech.hasSavedConnection) TextButton(enabled = !speech.busy, onClick = { speech.forgetConnection() }) { Text(stringResource(R.string.forget_connection)) }
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
internal fun TranscriptionRow(file: File, recordedAt: String, speech: SpeechConnection, local: LocalSummary, offline: OfflineSpeechModels) {
	var confirm by remember { mutableStateOf(false) }
	var offlineConfirm by remember { mutableStateOf(false) }
	var edit by remember { mutableStateOf(false) }
	val video = file.extension == "youtube"
	ImportTranscript(file, speech)
	val link = if (video) produceState("", file) {
		value = withContext(Dispatchers.IO) {
			try { file.readText() } catch (_: IOException) { "" }
		}
	}.value else ""
	if (link.isNotEmpty()) Text(link)
	val saved by produceState(SavedTranscript(""), file, speech.revision) {
		value = withContext(Dispatchers.IO) { loadSavedTranscript(file) }
	}
	if (!video && offline.installed) TextButton(enabled = !speech.busy && !offline.busy, onClick = {
		if (saved.text.isBlank()) speech.recognize(file, offline) else offlineConfirm = true
	}) { Text(stringResource(R.string.offline_asr)) }
	if (offlineConfirm) AlertDialog(onDismissRequest = { offlineConfirm = false }, text = { Text(stringResource(R.string.replace_transcript)) },
		confirmButton = { TextButton(onClick = { offlineConfirm = false; speech.recognize(file, offline) }) { Text(stringResource(R.string.offline_asr)) } },
		dismissButton = { TextButton(onClick = { offlineConfirm = false }) { Text(stringResource(R.string.cancel)) } })
	if (saved.text.isNotEmpty()) {
		TextButton(enabled = !speech.busy, onClick = { edit = true }) { Text(stringResource(R.string.edit_transcript)) }
		if (edit) {
			var draft by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(saved.text) }
			AlertDialog(onDismissRequest = { edit = false }, title = { Text(stringResource(R.string.edit_transcript)) },
				text = { OutlinedTextField(draft, { draft = it }) },
				confirmButton = { TextButton(enabled = draft.isNotBlank() && !speech.busy, onClick = { speech.edit(file, draft); edit = false }) { Text(stringResource(R.string.done)) } },
				dismissButton = { TextButton(onClick = { edit = false }) { Text(stringResource(R.string.cancel)) } })
		}
		saved.receipt?.let { receipt ->
			Text(stringResource(R.string.receipt_model, receipt.model), style = MaterialTheme.typography.labelLarge)
			receipt.provider?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
			receipt.requests?.let { Text(stringResource(R.string.receipt_requests, it), style = MaterialTheme.typography.bodySmall) }
			receipt.totalTokens?.let { Text(stringResource(R.string.receipt_tokens, it), style = MaterialTheme.typography.bodySmall) }
			val fee = receipt.costUsd?.let { stringResource(R.string.receipt_fee, it) } ?: stringResource(R.string.receipt_no_fee)
			Text(stringResource(R.string.receipt_time, receipt.elapsedMs / 1000.0) + " · " + fee,
				style = MaterialTheme.typography.bodySmall)
		}
		SelectionContainer { Text(saved.text) }
		TranscriptDocuments(file, saved.text)
		SlackShare(file, saved.text)
		TranscriptHistory(file, saved.text, speech.revision)
		TranscriptTools(file, saved.text, speech)
		TranscriptTimeline(file, speech.revision)
		TranscriptChat(file, saved.text, speech)
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
					if (speech.protocol == SpeechProtocol.Multipart) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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
