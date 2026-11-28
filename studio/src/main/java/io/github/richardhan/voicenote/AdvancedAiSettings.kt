package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

@Composable
private fun SecondsField(label: Int, value: Int, range: IntRange, update: (Int) -> Unit) {
	var draft by remember { mutableStateOf(value.toString()) }
	OutlinedTextField(draft, { draft = it; it.toIntOrNull()?.takeIf(range::contains)?.let(update) },
		label = { Text(stringResource(label) + " (${range.first}–${range.last} s)") }, singleLine = true,
		isError = draft.toIntOrNull()?.let(range::contains) != true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
internal fun AdvancedAiSettings(speech: SpeechConnection) {
	var open by remember { mutableStateOf(false) }
	TextButton(onClick = { open = !open }) { Text(stringResource(R.string.ai_advanced)) }
	if (!open) return
	SecondsField(R.string.ai_live_interval, speech.liveSeconds, 15..600) { speech.liveSeconds = it }
	SecondsField(R.string.ai_overlap, speech.overlapSeconds, 0..5) { speech.overlapSeconds = it }
	SecondsField(R.string.meeting_live_highlights, speech.highlightSeconds, 15..600) { speech.highlightSeconds = it }
	SecondsField(R.string.ai_first, speech.firstHighlightSeconds, 0..600) { speech.firstHighlightSeconds = it }
	OutlinedTextField(speech.highlightPrompt, { speech.highlightPrompt = it }, label = { Text(stringResource(R.string.ai_highlight_prompt)) })
	if (speech.highlightPrompt.isNotEmpty()) TextButton(onClick = { speech.highlightPrompt = "" }) { Text(stringResource(R.string.ai_restore_prompt)) }
	FilterChip(selected = speech.followSpeechForHighlights, onClick = { speech.followSpeechForHighlights = !speech.followSpeechForHighlights }, label = { Text(stringResource(R.string.ai_highlight_connection)) })
	FilterChip(selected = speech.cloudFirst, onClick = { speech.cloudFirst = !speech.cloudFirst }, label = { Text(stringResource(R.string.ai_cloud_first)) })
	FilterChip(selected = speech.defaultCloud, onClick = { speech.defaultCloud = !speech.defaultCloud }, label = { Text(stringResource(R.string.ai_cloud_default)) })
	FilterChip(selected = speech.correctLive, onClick = { speech.correctLive = !speech.correctLive }, label = { Text(stringResource(R.string.ai_correction_destination, speech.textDestination)) })
	FilterChip(selected = speech.compressAudio, onClick = { speech.compressAudio = !speech.compressAudio }, label = { Text(stringResource(R.string.ai_compress)) })
	FilterChip(selected = speech.warnLength, onClick = { speech.warnLength = !speech.warnLength }, label = { Text(stringResource(R.string.ai_warn)) })
	FilterChip(selected = speech.retrySegments, onClick = { speech.retrySegments = !speech.retrySegments }, label = { Text(stringResource(R.string.ai_retry_segments)) })
	FilterChip(selected = speech.summaryNotes, onClick = { speech.summaryNotes = !speech.summaryNotes }, label = { Text(stringResource(R.string.ai_use_agenda)) })
	OutlinedTextField(speech.defaultSummary, { speech.defaultSummary = it }, label = { Text(stringResource(R.string.ai_custom_summary)) })
}
