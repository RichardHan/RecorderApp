package com.eva.ui.photos

import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.eva.ui.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI-only model: core does not depend on recording persistence. */
@Immutable
data class PhotoItem(val id: String, val atMs: Long, val path: String, val caption: String)

@Composable
fun PhotoThumbnail(photo: PhotoItem, onSeek: ((Long) -> Unit)? = null) {
	var full by remember { mutableStateOf(false) }
	Column {
		PhotoImage(photo, false, Modifier.size(96.dp).clickable { full = true })
		TextButton(onClick = { onSeek?.invoke(photo.atMs) }, enabled = onSeek != null) { Text(photoTime(photo.atMs)) }
	}
	if (full) PhotoFullScreen(photo) { full = false }
}

private fun photoTime(ms: Long) = "%02d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Composable
private fun PhotoImage(photo: PhotoItem, full: Boolean, modifier: Modifier) {
	val bitmap by produceState<ImageBitmap?>(null, photo.path, full) {
		value = withContext(Dispatchers.IO) { runCatching {
			ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(photo.path))) { d, info, _ ->
				val scale = minOf(1.0, (if (full) 1600.0 else 240.0) / maxOf(info.size.width, info.size.height))
				d.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
			}.asImageBitmap()
		}.getOrNull() }
	}
	bitmap?.let { Image(it, photo.caption.ifBlank { stringResource(R.string.photo_image) }, modifier, contentScale = ContentScale.Fit) }
		?: Text(stringResource(R.string.photo_loading), modifier)
}

@Composable
private fun PhotoFullScreen(photo: PhotoItem, close: () -> Unit) {
	Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
		Surface(Modifier.fillMaxSize()) {
			Column(Modifier.fillMaxSize().padding(16.dp)) {
				TextButton(close) { Text(stringResource(R.string.photo_close)) }
				PhotoImage(photo, true, Modifier.fillMaxWidth().weight(1f))
				Text(photo.caption)
			}
		}
	}
}

@Composable
fun PhotoAttachments(
	owner: String?,
	timeMs: () -> Long,
	photos: List<PhotoItem>,
	onAdd: suspend (String, Long, Uri, String) -> Unit,
	onCaption: suspend (String, String) -> Unit,
	onDelete: suspend (String) -> Unit,
	onSeek: ((Long) -> Unit)? = null,
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var open by rememberSaveable { mutableStateOf(false) }
	var launchOwner by rememberSaveable { mutableStateOf<String?>(null) }
	var launchTime by rememberSaveable { mutableStateOf(0L) }
	var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
	var busy by remember { mutableStateOf(false) }
	var error by rememberSaveable { mutableStateOf(false) }
	var saved by rememberSaveable { mutableStateOf(false) }
	var editing by remember { mutableStateOf<PhotoItem?>(null) }
	var deleting by remember { mutableStateOf<PhotoItem?>(null) }
	fun receive(uri: Uri?) {
		val targetOwner = launchOwner
		val at = launchTime
		val temporary = cameraPath
		cameraPath = null
		launchOwner = null
		if (uri == null || targetOwner == null) { temporary?.let { File(it).delete() }; return }
		busy = true
		scope.launch {
			try { onAdd(targetOwner, at, uri, ""); saved = true }
			catch (e: CancellationException) { throw e }
			catch (_: Exception) { error = true }
			finally { temporary?.let { File(it).delete() }; busy = false }
		}
	}
	val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
		receive(if (ok) cameraPath?.let { FileProvider.getUriForFile(context, "${context.packageName}.provider", File(it)) } else null)
	}
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { receive(it) }
	OutlinedButton(onClick = { open = true }, enabled = owner != null || photos.isNotEmpty()) {
		Text(stringResource(R.string.photo_button, photos.size))
	}
	if (open) AlertDialog(
		onDismissRequest = { open = false }, title = { Text(stringResource(R.string.photo_title)) },
		text = { Column {
			Text(stringResource(R.string.photo_local_hint), style = MaterialTheme.typography.bodySmall)
			if (saved) Text(stringResource(R.string.photo_added), style = MaterialTheme.typography.bodySmall)
			if (error) Text(stringResource(R.string.photo_failed), color = MaterialTheme.colorScheme.error)
			if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
			Row {
				TextButton(enabled = owner != null && !busy && photos.size < 30 && launchOwner == null, onClick = {
					launchOwner = owner; launchTime = timeMs().coerceAtLeast(0); error = false; saved = false
					try {
						val dir = File(context.cacheDir, "photo_capture").apply { mkdirs() }
						val file = File(dir, "${UUID.randomUUID()}.jpg")
						cameraPath = file.absolutePath
						camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
					} catch (_: Exception) { cameraPath?.let { File(it).delete() }; cameraPath = null; launchOwner = null; error = true }
				}) { Text(stringResource(R.string.photo_camera)) }
				TextButton(enabled = owner != null && !busy && photos.size < 30 && launchOwner == null, onClick = {
					launchOwner = owner; launchTime = timeMs().coerceAtLeast(0); error = false; saved = false
					try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
					catch (_: Exception) { launchOwner = null; error = true }
				}) { Text(stringResource(R.string.photo_pick)) }
			}
			LazyColumn(Modifier.heightIn(max = 340.dp)) {
				items(photos, key = { it.id }) { photo ->
					Row {
						PhotoThumbnail(photo, onSeek)
						Column(Modifier.weight(1f)) {
							Text(photo.caption)
							TextButton({ editing = photo }) { Text(stringResource(R.string.photo_caption)) }
							TextButton({ deleting = photo }) { Text(stringResource(R.string.photo_delete)) }
						}
					}
				}
			}
		} },
		confirmButton = { TextButton({ open = false }) { Text(stringResource(R.string.photo_close)) } },
	)
	editing?.let { photo ->
		var caption by remember(photo.id) { mutableStateOf(photo.caption) }
		AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.photo_caption)) },
			text = { OutlinedTextField(caption, { caption = it.take(80) }, label = { Text(stringResource(R.string.photo_caption_hint)) }) },
			confirmButton = { TextButton({ scope.launch { try { onCaption(photo.id, caption); editing = null } catch (_: Exception) { error = true } } }) { Text(stringResource(R.string.photo_save)) } },
			dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.photo_close)) } })
	}
	deleting?.let { photo -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.photo_delete_confirm)) },
		confirmButton = { TextButton({ scope.launch { try { onDelete(photo.id); deleting = null } catch (_: Exception) { error = true } } }) { Text(stringResource(R.string.photo_delete)) } },
		dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.photo_close)) } }) }
}
