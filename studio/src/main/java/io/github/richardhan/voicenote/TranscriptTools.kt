package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val words = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}]|[\p{L}\p{N}]+""")
private val identifiers = Regex("""[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}|(?<!\d)(?:\+?\d[ ().-]?){8,15}(?!\d)""")
internal fun cleanTranscript(text: String) = text.lines().map { it.trim().replace(Regex("[\\t ]+"), " ") }
	.fold(mutableListOf<String>()) { lines, line -> lines.apply { if (line.isNotBlank() || lastOrNull()?.isNotBlank() == true) add(line) } }.joinToString("\n").trim()
internal fun maskIdentifiers(text: String) = identifiers.replace(text, "[…]")
internal fun pace(text: String, durationMs: Long): Int? {
	if (durationMs <= 0 || text.isBlank()) return null
	return (words.findAll(text).count() * 60000L / durationMs).toInt()
}

@Composable
internal fun TranscriptTools(file: File, text: String, speech: SpeechConnection) {
	var open by remember { mutableStateOf(false) }
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.text_tools)) }
	if (!open) return
	var draft by remember(text) { mutableStateOf(text) }
	val duration by produceState(0L, file) {
		value = withContext(Dispatchers.IO) {
			if (file.extension !in audioTypes) return@withContext 0L
			val meta = android.media.MediaMetadataRetriever()
			try { meta.setDataSource(file.path); meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
			catch (_: Exception) { 0L } finally { meta.release() }
		}
	}
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.text_tools)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			pace(text, duration)?.let { Text(stringResource(R.string.speech_pace, it)) }
			TextButton(onClick = { draft = cleanTranscript(draft) }) { Text(stringResource(R.string.clean_text)) }
			TextButton(onClick = { draft = maskIdentifiers(draft) }) { Text(stringResource(R.string.mask_contacts)) }
			OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.transcript)) })
		}
	}, confirmButton = { TextButton(enabled = !speech.busy && draft.isNotBlank() && draft != text, onClick = { speech.edit(file, draft); open = false }) {
		Text(stringResource(R.string.done))
	} }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
}
