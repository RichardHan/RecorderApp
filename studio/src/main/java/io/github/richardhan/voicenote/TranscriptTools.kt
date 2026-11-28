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
	var keyword by remember(file) { mutableStateOf("") }
	val keywords by produceState(emptyList<String>(), text) { value = withContext(Dispatchers.Default) { transcriptKeywords(text) } }
	val fillers by produceState(emptyList<Pair<String, Int>>(), text) { value = withContext(Dispatchers.Default) { transcriptFillers(text) } }
	val paceSegments by produceState(emptyList<Pair<TimedText, Int>>(), file, text) {
		value = withContext(Dispatchers.IO) {
			readTiming(file).filter { it.end - it.start >= 20 }.mapNotNull { line ->
				pace(line.text, ((line.end - line.start) * 1000).toLong())?.let { line to it }
			}.sortedBy { it.second }.let { listOfNotNull(it.firstOrNull(), it.lastOrNull()).distinct() }
		}
	}
	val context = androidx.compose.ui.platform.LocalContext.current
	val library = (context.applicationContext as StudioApplication).library
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
			FlowRow { for (word in keywords) TextButton(onClick = { keyword = word }) { Text(word) } }
			for ((word, count) in fillers) Text(stringResource(R.string.meeting_filler, word, count))
			for ((line, rate) in paceSegments) TextButton(enabled = library.started == 0L, onClick = { context.playRecording(file, (line.start * 1000).toLong()) }) {
				Text(formatClipTime((line.start * 1000).toInt()) + " · " + stringResource(R.string.speech_pace, rate))
			}
			pace(text, duration)?.let { Text(stringResource(R.string.speech_pace, it)) }
			TextButton(onClick = { draft = cleanTranscript(draft) }) { Text(stringResource(R.string.clean_text)) }
			TextButton(onClick = { draft = maskIdentifiers(draft) }) { Text(stringResource(R.string.mask_contacts)) }
			OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.transcript)) })
		}
	}, confirmButton = { TextButton(enabled = !speech.busy && draft.isNotBlank() && draft != text, onClick = { speech.edit(file, draft); open = false }) {
		Text(stringResource(R.string.done))
	} }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
	if (keyword.isNotBlank()) MatchedTranscript(file, keyword, speech.revision) { keyword = "" }
}
