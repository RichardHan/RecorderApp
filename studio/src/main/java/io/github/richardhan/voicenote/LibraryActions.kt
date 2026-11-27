package io.github.richardhan.voicenote

import android.content.ClipData
import android.content.Intent
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

internal fun LibraryCatalog.title(file: File) = value(file, "title").ifBlank {
	DateFormat.getDateTimeInstance().format(Date(file.lastModified()))
}

@Composable
internal fun LibraryFilters(library: RecordingLibrary, category: String, select: (String) -> Unit, favorites: Boolean, changeFavorites: () -> Unit) {
	var menu by remember { mutableStateOf(false) }
	var trash by rememberSaveable { mutableStateOf(false) }
	FlowRow {
		FilterChip(selected = favorites, onClick = changeFavorites, label = { Text(stringResource(R.string.favorites)) })
		Box {
			TextButton(onClick = { menu = true }) { Text(category.ifEmpty { stringResource(R.string.all_categories) }) }
			DropdownMenu(menu, { menu = false }) {
				for (label in listOf("") + library.files.map { library.catalog.value(it, "category") }.filter { it.isNotEmpty() }.distinct().sorted()) {
					DropdownMenuItem(text = { Text(label.ifEmpty { stringResource(R.string.all_categories) }) }, onClick = { select(label); menu = false })
				}
			}
		}
		TextButton(onClick = { trash = true }) { Text(stringResource(R.string.trash)) }
	}
	if (trash) AlertDialog(onDismissRequest = { trash = false }, title = { Text(stringResource(R.string.trash)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			if (library.trashedFiles.isEmpty()) Text(stringResource(R.string.empty))
			for (file in library.trashedFiles) {
				Text(library.catalog.title(file))
				TextButton(onClick = { library.catalog.update(file, "trashed" to "") }) { Text(stringResource(R.string.restore)) }
			}
		}
	}, confirmButton = { TextButton(onClick = { trash = false }) { Text(stringResource(R.string.done)) } })
}

@Composable
internal fun RecordingActions(file: File, library: RecordingLibrary) {
	val context = LocalContext.current
	val catalog = library.catalog
	val scope = rememberCoroutineScope()
	var open by rememberSaveable { mutableStateOf(false) }
	val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
		if (uri != null) {
			val markers = catalog.markers(file)
			scope.launch {
				try {
					withContext(Dispatchers.IO) {
						requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(markerCsv(markers)) }
					}
				} catch (e: CancellationException) { throw e }
				catch (_: Exception) { library.message = R.string.document_error }
			}
		}
	}
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.details)) }
	if (!open) return
	var title by rememberSaveable { mutableStateOf(catalog.value(file, "title")) }
	var category by rememberSaveable { mutableStateOf(catalog.value(file, "category")) }
	var notes by rememberSaveable { mutableStateOf(catalog.value(file, "notes")) }
	var label by rememberSaveable { mutableStateOf("") }
	val markers = catalog.markers(file)
	AlertDialog(onDismissRequest = { open = false }, title = { Text(catalog.title(file)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			catalog.value(file, "location").takeIf { it.isNotBlank() }?.let { Text(it) }
			catalog.value(file, "device").takeIf { it.isNotBlank() }?.let { Text(it) }
			OutlinedTextField(title, { title = it }, singleLine = true, label = { Text(stringResource(R.string.recording_name)) })
			OutlinedTextField(category, { category = it }, singleLine = true, label = { Text(stringResource(R.string.category)) })
			OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(R.string.notes)) })
			val favorite = catalog.value(file, "favorite").isNotEmpty()
			FilterChip(selected = favorite, onClick = { catalog.update(file, "favorite" to if (favorite) "" else "1") }, label = { Text(stringResource(R.string.favorites)) })
			if (file.extension in audioTypes) {
				OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(stringResource(R.string.bookmark_name)) })
				TextButton(enabled = library.playingFile == file, onClick = {
					catalog.saveMarkers(file, markers + RecordingMarker(library.playbackPosition(), label.trim()))
					label = ""
				}) { Text(stringResource(R.string.bookmark)) }
				for ((index, marker) in markers.withIndex()) Row {
					TextButton(modifier = Modifier.weight(1f), enabled = library.started == 0L, onClick = { context.playRecording(file, marker.ms) }) {
						Text(formatClipTime(marker.ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) + " " + marker.label)
					}
					TextButton(onClick = { catalog.saveMarkers(file, markers.filterIndexed { i, _ -> i != index }) }) { Text(stringResource(R.string.remove_marker)) }
				}
				if (markers.isNotEmpty()) TextButton(onClick = { csv.launch(file.nameWithoutExtension + "-bookmarks.csv") }) { Text(stringResource(R.string.export_markers)) }
			}
			TextButton(onClick = {
				try {
					val send = Intent(Intent.ACTION_SEND)
					if (file.extension == "youtube") send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, file.readText())
					else {
						val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
						send.setType(audioTypes.getValue(file.extension)).putExtra(Intent.EXTRA_STREAM, uri)
						send.clipData = ClipData.newRawUri(file.name, uri)
						send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					}
					context.startActivity(Intent.createChooser(send, null))
				} catch (_: Exception) { library.message = R.string.document_error }
			}) { Text(stringResource(R.string.share_recording)) }
			TextButton(onClick = { open = false; library.trash(file) }) { Text(stringResource(R.string.move_trash)) }
		}
	}, confirmButton = {
		TextButton(onClick = { catalog.update(file, "title" to title.trim(), "category" to category.trim(), "notes" to notes); open = false }) {
			Text(stringResource(R.string.done))
		}
	})
}
