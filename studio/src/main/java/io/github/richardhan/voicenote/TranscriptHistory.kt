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
internal fun TranscriptHistory(file: File, current: String, revision: Int) {
	val versions by produceState(emptyList<File>(), file, revision) {
		value = withContext(Dispatchers.IO) { historyDirectory(file).listFiles().orEmpty().filter { !it.name.startsWith(".") && File(it, "text.txt").isFile }.sortedByDescending { it.name } }
	}
	if (versions.isEmpty()) return
	var open by remember { mutableStateOf(false) }
	var selected by remember { mutableStateOf<File?>(null) }
	TextButton(onClick = { selected = versions.first(); open = true }) { Text(stringResource(R.string.versions)) }
	if (!open) return
	val previous by produceState("", selected) {
		value = withContext(Dispatchers.IO) { selected?.let { File(it, "text.txt").takeIf(File::exists)?.readText() }.orEmpty() }
	}
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.versions)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			for (dir in versions) TextButton(onClick = { selected = dir }) {
				Text(DateFormat.getDateTimeInstance().format(Date(dir.name.substringBefore('-').toLongOrNull() ?: dir.lastModified())))
			}
			Text(stringResource(R.string.previous_text), style = MaterialTheme.typography.titleMedium)
			SelectionContainer { Text(previous) }
			HorizontalDivider()
			Text(stringResource(R.string.current_text), style = MaterialTheme.typography.titleMedium)
			SelectionContainer { Text(current) }
		}
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}
