package com.eva.feature_player.cloud

import android.widget.Toast
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.model.TranscriptExportExtras
import com.eva.cloudtranscribe.model.TranscriptExportFormat
import com.eva.cloudtranscribe.model.TranscriptExportFormatter
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.feature_player.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retains an immutable export snapshot across Activity/process recreation without putting long text in a Bundle. */
@HiltViewModel
class TranscriptExportViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val state: SavedStateHandle,
) : ViewModel() {
	var busy by mutableStateOf(state.get<String>("pendingExport") != null)
		private set

	var needsDestination by mutableStateOf(state.get<Boolean>("needsDestination") == true)
		private set

	var interrupted by mutableStateOf(state.get<Boolean>("writing") == true)
		private set
	init { if (interrupted) clear() }

	/** Format of the pending snapshot (drives the picker's MIME type and suggested name). */
	val pendingFormat: TranscriptExportFormat
		get() = state.get<String>("pendingFormat")?.let { runCatching { TranscriptExportFormat.valueOf(it) }.getOrNull() } ?: TranscriptExportFormat.TXT

	val pendingFileName: String?
		get() = state.get<String>("pendingFileName")

	fun prepare(
		record: TranscriptRecord,
		format: TranscriptExportFormat = TranscriptExportFormat.TXT,
		extras: TranscriptExportExtras = TranscriptExportExtras(),
	): Job? {
		if (busy) return null
		busy = true
		interrupted = false
		return viewModelScope.launch {
			try {
				val name = withContext(Dispatchers.IO) {
					val text = TranscriptExportFormatter.render(format, record, extras)
					val dir = File(context.cacheDir, "transcript-exports").apply { mkdirs() }
					val file = File.createTempFile("pending-", ".${format.extension}", dir)
					try { file.writeText(text, Charsets.UTF_8); file.name }
					catch (e: Exception) { file.delete(); throw e }
				}
				state["pendingExport"] = name
				state["pendingFormat"] = format.name
				state["pendingFileName"] = TranscriptExportFormatter.fileName(extras.title, extras.recordedAt, record.recordingId, format)
				state["needsDestination"] = true
				needsDestination = true
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { clear(); notify(false) }
		}
	}

	fun pickerLaunched() { state["needsDestination"] = false; needsDestination = false }
	fun pickerFailed() { clear(); notify(false) }

	fun destinationChosen(uri: Uri?) = viewModelScope.launch {
		if (uri == null) { clear(); return@launch }
		state["writing"] = true
		try {
			val name = state.get<String>("pendingExport") ?: error("Missing snapshot")
			withContext(Dispatchers.IO) {
				val file = File(File(context.cacheDir, "transcript-exports"), name)
				val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
				stream.use { output -> file.inputStream().use { it.copyTo(output) } }
			}
			notify(true)
		} catch (e: CancellationException) { throw e }
		catch (_: Exception) { notify(false) }
		finally { clear() }
	}

	private fun clear() {
		state.get<String>("pendingExport")?.let { File(File(context.cacheDir, "transcript-exports"), it).delete() }
		state.remove<String>("pendingExport")
		state.remove<Boolean>("needsDestination")
		state.remove<Boolean>("writing")
		state.remove<String>("pendingFormat")
		state.remove<String>("pendingFileName")
		needsDestination = false
		busy = false
	}
	private fun notify(success: Boolean) = Toast.makeText(context, if (success) R.string.transcript_export_saved else R.string.transcript_export_failed, Toast.LENGTH_LONG).show()
}

/**
 * Exports only after explicit confirmation and the system destination picker. No automatic sharing.
 * TXT / Markdown always; SRT only when the transcript kept real sentence timing (never estimated).
 */
@Composable
internal fun TranscriptExportButton(
	record: TranscriptRecord,
	extras: () -> TranscriptExportExtras = { TranscriptExportExtras() },
	viewModel: TranscriptExportViewModel = hiltViewModel(key = "transcript-export-${record.recordingId}"),
) {
	var confirm by remember(record.id) { mutableStateOf(false) }
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(viewModel.pendingFormat.mimeType)) { uri -> viewModel.destinationChosen(uri) }
	LaunchedEffect(viewModel.needsDestination) {
		if (viewModel.needsDestination) {
			try { launcher.launch(viewModel.pendingFileName ?: "transcript-${record.recordingId}.${viewModel.pendingFormat.extension}"); viewModel.pickerLaunched() }
			catch (_: Exception) { viewModel.pickerFailed() }
		}
	}
	if (viewModel.interrupted) Text(stringResource(R.string.transcript_export_failed))
	OutlinedButton(onClick = { confirm = true }, enabled = !viewModel.busy) { Text(stringResource(R.string.transcript_export_button)) }
	if (confirm) {
		val formats = remember(record.id, record.updatedAtMillis) { TranscriptExportFormatter.availableFormats(record) }
		val hasText = remember(record.id, record.updatedAtMillis) { TranscriptExportFormatter.hasExportableText(record) }
		val available = remember(confirm) { extras() }
		var format by remember(record.id) { mutableStateOf(TranscriptExportFormat.TXT) }
		var withSummary by remember(record.id) { mutableStateOf(true) }
		var withBookmarks by remember(record.id) { mutableStateOf(true) }
		AlertDialog(
			onDismissRequest = { confirm = false },
			title = { Text(stringResource(R.string.transcript_export_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					if (!hasText) {
						Text(stringResource(R.string.transcript_export_empty))
						return@Column
					}
					Text(stringResource(R.string.transcript_export_format), style = MaterialTheme.typography.titleSmall)
					formats.forEach { option ->
						Row(
							verticalAlignment = Alignment.CenterVertically,
							modifier = Modifier.selectable(selected = format == option, onClick = { format = option }),
						) {
							RadioButton(selected = format == option, onClick = { format = option })
							Text(option.label)
						}
					}
					if (TranscriptExportFormat.SRT !in formats) Text(
						stringResource(R.string.transcript_export_no_srt),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					if (format != TranscriptExportFormat.SRT) {
						if (!available.summary.isNullOrBlank()) CheckRow(stringResource(R.string.transcript_export_with_summary), withSummary) { withSummary = it }
						if (available.bookmarks.isNotEmpty()) CheckRow(stringResource(R.string.transcript_export_with_bookmarks), withBookmarks) { withBookmarks = it }
					}
					Text(stringResource(R.string.transcript_export_notice), style = MaterialTheme.typography.bodySmall)
				}
			},
			confirmButton = {
				TextButton(enabled = hasText, onClick = {
					confirm = false
					val chosen = available.copy(
						summary = available.summary.takeIf { withSummary },
						summaryModel = available.summaryModel.takeIf { withSummary },
						bookmarks = if (withBookmarks) available.bookmarks else emptyList(),
					)
					viewModel.prepare(record, format, chosen)
				}) { Text(stringResource(R.string.transcript_export_continue)) }
			},
			dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.transcript_export_cancel)) } },
		)
	}
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Checkbox(checked = checked, onCheckedChange = onChange)
		Text(label)
	}
}
