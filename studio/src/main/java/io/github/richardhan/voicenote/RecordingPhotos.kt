package io.github.richardhan.voicenote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

private fun photoDir(context: Context, file: File) = File(File(context.noBackupFilesDir, "photos"), file.name)
private fun photos(context: Context, file: File) = photoDir(context, file).listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }
private fun photoTime(file: File) = file.name.substringBefore('-').toLong()

internal fun copyPhoto(context: Context, uri: Uri, file: File, ms: Long): File {
	val dir = photoDir(context, file)
	check(dir.isDirectory || dir.mkdirs())
	check(photos(context, file).size < 30)
	val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
		val scale = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
		decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
		decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
	}
	val temp = File.createTempFile("photo-", ".part", dir)
	try {
		temp.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
		val saved = File(dir, ms.toString().padStart(12, '0') + "-" + UUID.randomUUID() + ".jpg")
		check(temp.renameTo(saved))
		return saved
	} finally { image.recycle(); temp.delete() }
}

internal typealias AddPhoto = (File, Long, Boolean) -> Unit

@Composable
internal fun rememberPhotoIntake(library: RecordingLibrary): AddPhoto {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var name by rememberSaveable { mutableStateOf<String?>(null) }
	var time by rememberSaveable { mutableLongStateOf(0L) }
	var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }
	val cameraDir = File(context.cacheDir, "camera")
	fun accept(uri: Uri?) {
		val target = name?.let { File(File(context.filesDir, "audio"), it) }
		val temp = cameraFile?.let { File(cameraDir, it) }
		name = null
		cameraFile = null
		val ms = time
		scope.launch {
			try {
				if (target != null && uri != null && (target.isFile || target == library.captureTarget)) {
					val saved = withContext(Dispatchers.IO) { copyPhoto(context, uri, target, ms) }
					if (!target.isFile && target != library.captureTarget) saved.delete()
					else library.photosChanged()
				}
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { library.message = R.string.photo_error }
			finally { temp?.delete() }
		}
	}
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { accept(it) }
	val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
		val temp = cameraFile?.let { File(cameraDir, it) }
		accept(if (ok && temp != null) FileProvider.getUriForFile(context, context.packageName + ".files", temp) else null)
	}
	return { file, ms, take ->
		if (name == null) {
			name = file.name
			time = ms
			try {
				if (take) {
					cameraDir.mkdirs()
					val temp = File.createTempFile("camera-", ".jpg", cameraDir)
					cameraFile = temp.name
					camera.launch(FileProvider.getUriForFile(context, context.packageName + ".files", temp))
				} else picker.launch("image/*")
			} catch (_: Exception) { accept(null); library.message = R.string.photo_error }
		}
	}
}

@Composable
internal fun PhotoActions(library: RecordingLibrary, file: File, add: AddPhoto) {
	val context = LocalContext.current
	val pictures by produceState(emptyList<File>(), file, library.photoRevision) {
		value = withContext(Dispatchers.IO) { photos(context, file) }
	}
	var open by rememberSaveable { mutableStateOf(false) }
	fun position() = when (file) {
		library.captureTarget -> library.recordedDurationMs()
		library.playingFile -> library.playbackPosition()
		else -> 0L
	}
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.photos) + " (" + pictures.size + ")") }
	if (!open) return
	var zoom by remember { mutableStateOf<File?>(null) }
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.photos)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			FlowRow {
				TextButton(enabled = pictures.size < 30, onClick = { add(file, position(), false) }) { Text(stringResource(R.string.add_photo)) }
				TextButton(enabled = pictures.size < 30, onClick = { add(file, position(), true) }) { Text(stringResource(R.string.take_photo)) }
			}
			for (picture in pictures) key(picture) {
				val thumbnail by produceState<Bitmap?>(null, picture) { value = withContext(Dispatchers.IO) {
					BitmapFactory.decodeFile(picture.path, BitmapFactory.Options().apply { inSampleSize = 8 })
				} }
				thumbnail?.let { Image(it.asImageBitmap(), stringResource(R.string.photos), Modifier.size(90.dp).clickable { zoom = picture }) }
				val field = "photo." + picture.name
				OutlinedTextField(library.catalog.value(file, field), { library.catalog.update(file, field to it.take(80)) }, label = { Text(stringResource(R.string.caption)) })
				FlowRow {
					TextButton(enabled = library.started == 0L, onClick = { context.playRecording(file, photoTime(picture)) }) { Text(formatClipTime(photoTime(picture).toInt())) }
					TextButton(onClick = {
						if (picture.delete()) { library.catalog.update(file, field to ""); library.photosChanged() }
					}) { Text(stringResource(R.string.remove_marker)) }
				}
			}
		}
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
	zoom?.let { picture ->
		val bitmap by produceState<Bitmap?>(null, picture) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(picture.path) } }
		AlertDialog(onDismissRequest = { zoom = null }, text = { bitmap?.let { Image(it.asImageBitmap(), library.catalog.value(file, "photo." + picture.name)) } },
			confirmButton = { TextButton(onClick = { zoom = null }) { Text(stringResource(R.string.done)) } })
	}
}
