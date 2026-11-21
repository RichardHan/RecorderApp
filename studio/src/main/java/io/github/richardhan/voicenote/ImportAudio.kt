package io.github.richardhan.voicenote

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal val audioTypes = mapOf("m4a" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/wav", "flac" to "audio/flac", "ogg" to "audio/ogg", "aac" to "audio/aac", "webm" to "audio/webm")
private fun importedAudio(input: InputStream, dir: File) {
	val temp = File.createTempFile("import-", ".part", dir)
	try {
		input.use { source -> temp.outputStream().use { dest ->
			val buffer = ByteArray(65536)
			var count = 0L
			while (true) {
				val n = source.read(buffer)
				if (n < 0) break
				count += n
				require(count <= 512L * 1024 * 1024)
				dest.write(buffer, 0, n)
			}
		} }
		val meta = MediaMetadataRetriever()
		val type = try {
			meta.setDataSource(temp.path)
			require(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes")
			meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
		} finally { meta.release() }
		val ext = when (type) {
			"audio/x-wav" -> "wav"
			"application/ogg" -> "ogg"
			else -> audioTypes.entries.first { it.value == type }.key
		}
		check(temp.renameTo(File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.$ext")))
	} finally { temp.delete() }
}

internal fun importDocument(context: Context, dir: File, uri: Uri) =
	importedAudio(requireNotNull(context.contentResolver.openInputStream(uri)), dir)

internal fun importLink(dir: File, text: String) {
	val uri = URI(text.trim())
	require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
	if (uri.host.lowercase() in setOf("youtu.be", "www.youtube.com", "youtube.com", "m.youtube.com")) {
		val parsed = Uri.parse(uri.toString())
		val id = when {
			uri.host == "youtu.be" -> parsed.lastPathSegment
			uri.path == "/watch" -> parsed.getQueryParameter("v")
			parsed.pathSegments.firstOrNull() in setOf("shorts", "live") -> parsed.lastPathSegment
			else -> null
		}
		require(id != null && Regex("[A-Za-z0-9_-]{11}").matches(id))
		val tmp = File.createTempFile("link-", ".part", dir)
		try {
			tmp.writeText("https://www.youtube.com/watch?v=$id")
			check(tmp.renameTo(File(dir, "${System.currentTimeMillis()}-$id.youtube")))
		} finally { tmp.delete() }
	} else {
		val conn = uri.toURL().openConnection() as HttpsURLConnection
		try {
			conn.connectTimeout = 20000
			conn.readTimeout = 30000
			require(conn.responseCode in 200..299)
			importedAudio(conn.inputStream, dir)
		} finally { conn.disconnect() }
	}
}

@Composable
internal fun ImportActions(library: RecordingLibrary) {
	var show by remember { mutableStateOf(false) }
	var link by remember { mutableStateOf("") }
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) library.importSource(uri = uri)
	}
	Row {
		TextButton(enabled = !library.importing && library.started == 0L, onClick = { picker.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.import_file)) }
		TextButton(enabled = !library.importing, onClick = { show = true }) { Text(stringResource(R.string.import_url)) }
	}
	if (library.importing) LinearProgressIndicator()
	if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text(stringResource(R.string.import_url)) },
		text = { OutlinedTextField(link, { link = it }, label = { Text(stringResource(R.string.audio_url)) }, singleLine = true) },
		confirmButton = { TextButton(enabled = link.isNotBlank(), onClick = { library.importSource(url = link); show = false; link = "" }) { Text(stringResource(R.string.import_url)) } },
		dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.cancel)) } })
}
