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
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptTextExport
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

	fun prepare(record: TranscriptRecord): Job? {
		if (busy) return null
		busy = true
		interrupted = false
		return viewModelScope.launch {
			try {
				val name = withContext(Dispatchers.IO) {
					val dir = File(context.cacheDir, "transcript-exports").apply { mkdirs() }
					val file = File.createTempFile("pending-", ".txt", dir)
					try { file.writeText(TranscriptTextExport.render(record), Charsets.UTF_8); file.name }
					catch (e: Exception) { file.delete(); throw e }
				}
				state["pendingExport"] = name
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
		needsDestination = false
		busy = false
	}
	private fun notify(success: Boolean) = Toast.makeText(context, if (success) R.string.transcript_export_saved else R.string.transcript_export_failed, Toast.LENGTH_LONG).show()
}

/** Exports only after explicit confirmation and the system destination picker. No automatic sharing. */
@Composable
internal fun TranscriptExportButton(record: TranscriptRecord, viewModel: TranscriptExportViewModel = hiltViewModel(key = "transcript-export-${record.recordingId}")) {
	var confirm by remember(record.id) { mutableStateOf(false) }
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> viewModel.destinationChosen(uri) }
	LaunchedEffect(viewModel.needsDestination) {
		if (viewModel.needsDestination) {
			try { launcher.launch("transcript-${record.recordingId}.txt"); viewModel.pickerLaunched() }
			catch (_: Exception) { viewModel.pickerFailed() }
		}
	}
	if (viewModel.interrupted) Text(stringResource(R.string.transcript_export_failed))
	OutlinedButton(onClick = { confirm = true }, enabled = !viewModel.busy) { Text(stringResource(R.string.transcript_export_button)) }
	if (confirm) AlertDialog(
		onDismissRequest = { confirm = false },
		title = { Text(stringResource(R.string.transcript_export_title)) },
		text = { Text(stringResource(R.string.transcript_export_notice)) },
		confirmButton = { TextButton(onClick = {
			confirm = false
			viewModel.prepare(record)
		}) { Text(stringResource(R.string.transcript_export_continue)) } },
		dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.transcript_export_cancel)) } },
	)
}
