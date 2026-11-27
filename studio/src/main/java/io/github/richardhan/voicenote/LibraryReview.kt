package io.github.richardhan.voicenote

import android.os.BatteryManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun exportTranscripts(out: OutputStream, recordings: List<File>) {
	ZipOutputStream(out.buffered()).use { zip ->
		for (file in recordings) {
			val text = transcriptFile(file)
			if (!text.exists()) continue
			zip.putNextEntry(ZipEntry(file.name + ".txt"))
			text.inputStream().use { it.copyTo(zip) }
			zip.closeEntry()
		}
	}
}

@Composable
internal fun LibraryReview(library: RecordingLibrary, speech: SpeechConnection, revision: Int) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var open by rememberSaveable { mutableStateOf(false) }
	var selection by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
	var combined by remember { mutableStateOf("") }
	var failed by remember { mutableStateOf(false) }
	val archive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
		if (uri != null) {
			val files = library.files.filter { it.name in selection }
			scope.launch {
				try { withContext(Dispatchers.IO) { exportTranscripts(requireNotNull(context.contentResolver.openOutputStream(uri, "wt")), files) } }
				catch (e: CancellationException) { throw e }
				catch (_: Exception) { failed = true }
			}
		}
	}
	TextButton(onClick = { open = true; combined = "" }) { Text(stringResource(R.string.library_review)) }
	if (!open) return
	val files = library.files
	val receipts by produceState(emptyMap<File, SavedTranscript>(), files, revision) {
		value = withContext(Dispatchers.IO) { files.associateWith(::loadSavedTranscript).filterValues { it.text.isNotBlank() } }
	}
	val known = receipts.values.mapNotNull { it.receipt?.costUsd?.toBigDecimalOrNull() }
	val stats = stringResource(R.string.library_stats, files.size, receipts.size, files.sumOf(File::length) / 1048576.0)
	val battery = context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.library_review)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			Text(stats)
			if (known.isNotEmpty()) Text(stringResource(R.string.reported_total, known.sumOf { it }.toPlainString(), known.size))
			if (battery != null && battery in 0..100) Text(stringResource(R.string.device_stats, context.filesDir.usableSpace / 1048576, battery))
			for ((file, saved) in receipts) {
				val checked = file.name in selection
				FilterChip(selected = checked, onClick = {
					selection = ArrayList(if (checked) selection - file.name else selection + file.name)
					combined = ""
				}, label = { Text(library.catalog.title(file)) })
				Text(saved.text.take(160), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
			}
			val selected = receipts.filterKeys { it.name in selection }
			if (selected.isNotEmpty()) {
				TextButton(onClick = { archive.launch("studio-transcripts.zip") }) { Text(stringResource(R.string.export_selected)) }
				TextButton(onClick = {
					combined = selected.entries.joinToString("\n\n") { (file, saved) ->
						transcriptPassages(saved.text).joinToString("\n") { library.catalog.title(file) + " · " + it }
					}
				}) { Text(stringResource(R.string.ask_selected)) }
			}
			if (combined.isNotEmpty()) {
				val folder = File(context.noBackupFilesDir, "library-chats").apply { mkdirs() }
				TranscriptChat(File(folder, "collection"), combined, speech)
			}
			if (failed) Text(stringResource(R.string.document_error))
		}
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}
