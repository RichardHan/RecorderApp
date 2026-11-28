package io.github.richardhan.voicenote

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class DeviceAudio(val name: String, val uri: Uri)

@Composable
internal fun DeviceRecordings(library: RecordingLibrary) {
	val context = LocalContext.current
	var open by remember { mutableStateOf(false) }
	var denied by remember { mutableStateOf(false) }
	val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
	val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> open = granted; denied = !granted }
	TextButton(enabled = !library.importing, onClick = {
		if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { denied = false; open = true }
		else request.launch(permission)
	}) { Text(stringResource(R.string.device_audio)) }
	if (denied) Text(stringResource(R.string.device_audio_permission))
	if (!open) return
	var recordingsOnly by remember { mutableStateOf(Build.VERSION.SDK_INT >= 31) }
	var failed by remember(recordingsOnly) { mutableStateOf(false) }
	val files by produceState<List<DeviceAudio>?>(null, recordingsOnly) {
		value = null
		try {
			value = withContext(Dispatchers.IO) {
				val media = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
				val query = if (Build.VERSION.SDK_INT >= 31 && recordingsOnly) "${MediaStore.Audio.Media.IS_RECORDING}=1" else null
				requireNotNull(context.contentResolver.query(media, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME), query, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")).use { rows ->
					buildList { while (rows.moveToNext()) add(DeviceAudio(rows.getString(1).orEmpty(), ContentUris.withAppendedId(media, rows.getLong(0)))) }
				}
			}
		} catch (e: CancellationException) { throw e }
		catch (_: Exception) { failed = true; value = emptyList() }
	}
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.device_audio)) }, text = {
		Column {
			if (Build.VERSION.SDK_INT >= 31) FilterChip(selected = recordingsOnly, onClick = { recordingsOnly = !recordingsOnly }, label = { Text(stringResource(R.string.only_recordings)) })
			if (files == null) LinearProgressIndicator()
			else if (failed) Text(stringResource(R.string.import_error))
			else if (files!!.isEmpty()) Text(stringResource(R.string.no_matches))
			else LazyColumn { items(files.orEmpty(), key = { it.uri.toString() }) { audio ->
				TextButton(onClick = { library.importDocuments(listOf(audio.uri)); open = false }) { Text(audio.name) }
			} }
		}
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}
