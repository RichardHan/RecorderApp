package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

private fun historyDirectory(file: File) = File(file.parentFile, file.name + ".history")

/** Preserve the last visible transcript, timestamps and receipt together before a replacement. */
@Synchronized
internal fun archiveTranscript(file: File) {
	val text = transcriptFile(file)
	if (!text.isFile) return
	val parent = historyDirectory(file)
	check(parent.isDirectory || parent.mkdirs())
	val nonce = UUID.randomUUID()
	val temp = File(parent, ".$nonce").apply { check(mkdir()) }
	try {
		text.copyTo(File(temp, "text.txt"))
		for (source in listOf(timingFile(file), File(file.parentFile, file.name + ".usage"))) {
			if (source.isFile) source.copyTo(File(temp, source.extension))
		}
		check(temp.renameTo(File(parent, "${System.currentTimeMillis()}-$nonce")))
	} finally { if (temp.exists()) temp.deleteRecursively() }
}

@Composable
internal fun TranscriptHistory(file: File, current: String, revision: Int, speech: SpeechConnection) {
	val versions by produceState(emptyList<File>(), file, revision) {
		value = withContext(Dispatchers.IO) { historyDirectory(file).listFiles().orEmpty().filter { !it.name.startsWith(".") && File(it, "text.txt").isFile }.sortedByDescending { it.name } }
	}
	if (versions.isEmpty()) return
	var open by remember { mutableStateOf(false) }
	var left by remember { mutableStateOf<File?>(null) }
	var right by remember { mutableStateOf<File?>(null) }
	var pickLeft by remember { mutableStateOf(true) }
	var confirm by remember { mutableStateOf(false) }
	fun label(dir: File?) = dir?.let { DateFormat.getDateTimeInstance().format(Date(it.name.substringBefore('-').toLongOrNull() ?: it.lastModified())) }
	TextButton(onClick = { left = versions.first(); right = null; open = true }) { Text(stringResource(R.string.versions)) }
	if (!open) return
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.versions)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			FlowRow {
				FilterChip(selected = pickLeft, onClick = { pickLeft = true }, label = { Text(stringResource(R.string.ai_left)) })
				FilterChip(selected = !pickLeft, onClick = { pickLeft = false }, label = { Text(stringResource(R.string.ai_right)) })
			}
			for (dir in listOf(null) + versions) TextButton(onClick = { if (pickLeft) left = dir else right = dir }) { Text(label(dir) ?: stringResource(R.string.current_text)) }
			Row { for (dir in listOf(left, right)) key(dir, pickLeft) {
				val words by produceState("", dir, current) { value = withContext(Dispatchers.IO) { dir?.let { File(it,"text.txt").readText() } ?: current } }
				Column(Modifier.weight(1f)) { Text(label(dir) ?: stringResource(R.string.current_text)); SelectionContainer { Text(words) } }
			} }
			val selected = if (pickLeft) left else right
			if (selected != null) {
				val summaries by produceState(emptyList<String>(), selected, speech.revision) {
					value = withContext(Dispatchers.IO) { selected.listFiles().orEmpty().filter { it.name.startsWith("summary-") }.sortedByDescending { it.name }.map { it.readText() } }
				}
				for (body in summaries) SelectionContainer { Text(body) }
				TextButton(enabled = speech.canAnalyze() && !speech.busy, onClick = { confirm = true }) { Text(stringResource(R.string.ai_summary_version)) }
				if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = { Text(stringResource(R.string.meeting_disclose, speech.textDestination, speech.chatModel)) },
					confirmButton = { TextButton(onClick = { confirm = false; speech.summarizeArchive(selected, speech.defaultSummary) }) { Text(stringResource(R.string.meeting_generate)) } },
					dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
			}
		}
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}

@Composable
internal fun DiscardAction(remove: () -> Unit) {
	var confirm by remember { mutableStateOf(false) }
	TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.meeting_remove)) }
	if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = { Text(stringResource(R.string.ai_discard_result)) },
		confirmButton = { TextButton(onClick = { confirm = false; remove() }) { Text(stringResource(R.string.meeting_remove)) } },
		dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun SummaryVersions(notes: MeetingNotes, revision: Int) {
	val versions by produceState(emptyList<File>(), notes, revision) { value = withContext(Dispatchers.IO) { notes.summaries() } }
	if (versions.isEmpty()) return
	var open by remember { mutableStateOf(false) }
	TextButton(onClick = { open = !open }) { Text(stringResource(R.string.ai_summary_history)) }
	if (open) for (f in versions) {
		val output by produceState<MeetingOutput?>(null, f) { value = withContext(Dispatchers.IO) { notes.readOutput(f) } }
		Text(DateFormat.getDateTimeInstance().format(Date(f.lastModified())))
		output?.points?.forEach { Text(it.text) }
	}
}
