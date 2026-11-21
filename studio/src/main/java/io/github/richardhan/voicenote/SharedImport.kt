package io.github.richardhan.voicenote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Messaging apps often add a title before the shared URL. Preserve the URL, including signed parameters. */
internal fun sharedHttpsLink(text: String): String? {
	val words = text.trim().splitToSequence(Regex("\\s+"))
	val link = words.filter { it.startsWith("https://", ignoreCase = true) }.singleOrNull() ?: return null
	val uri = try { URI(link) } catch (_: java.net.URISyntaxException) { return null }
	if (uri.host == null || uri.userInfo != null) return null
	return "https://" + link.substringAfter("://")
}

internal fun Intent.sharedImportSources(): List<Uri> {
	val mime = type?.substringBefore(';')?.trim().orEmpty()
	val clips = clipData
	if (action == Intent.ACTION_SEND_MULTIPLE) {
		if (!mime.startsWith("audio/", ignoreCase = true)) return emptyList()
		val streams: List<*> = IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java)
			?: clips?.let { clip -> List(clip.itemCount) { clip.getItemAt(it).uri } }.orEmpty()
		return streams.map { (it as? Uri)?.takeIf(Uri::isDocumentSource) ?: return emptyList() }.distinct()
	}
	if (action != Intent.ACTION_SEND || (clips?.itemCount ?: 0) > 1) return emptyList()
	val item = clips?.takeIf { it.itemCount == 1 }?.getItemAt(0)
	val source = when {
		mime.startsWith("audio/", ignoreCase = true) -> {
			val uri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java) ?: item?.uri
			uri?.takeIf(Uri::isDocumentSource)
		}
		mime.equals("text/plain", ignoreCase = true) -> {
			val text = getCharSequenceExtra(Intent.EXTRA_TEXT) ?: item?.text
			text?.toString()?.let(::sharedHttpsLink)?.let(Uri::parse)
		}
		else -> null
	}
	return listOfNotNull(source)
}

private fun Uri.isDocumentSource() = scheme == "content" && !authority.isNullOrBlank()

internal fun Context.sharedFileName(uri: Uri): String? = try {
	contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
		if (rows.moveToFirst()) rows.getString(0)?.take(200) else null
	}
} catch (_: RuntimeException) { null }

@Composable
internal fun SharedImportDialog(sources: List<Uri>, library: RecordingLibrary, dismiss: () -> Unit) {
	val first = sources.first()
	val audio = first.scheme == "content"
	val context = LocalContext.current
	AlertDialog(onDismissRequest = dismiss,
		title = { Text(if (sources.size > 1) stringResource(R.string.confirm_audio_batch, sources.size)
			else stringResource(if (audio) R.string.import_file else R.string.import_url)) },
		text = {
			if (audio) LazyColumn(Modifier.heightIn(max = 360.dp)) {
				itemsIndexed(sources) { index, uri ->
					val name by produceState<String?>(null, uri) { value = withContext(Dispatchers.IO) { context.sharedFileName(uri) } }
					val fallback = if (sources.size == 1) stringResource(R.string.shared_audio) else stringResource(R.string.audio_file_number, index + 1)
					Text(name?.takeIf { it.isNotBlank() } ?: fallback, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
				}
			} else Text(first.toString(), maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
		},
		confirmButton = {
			TextButton(enabled = !library.importing, onClick = {
				if (audio) library.importDocuments(sources) else library.importUrl(first.toString())
				dismiss()
			}) { Text(stringResource(R.string.confirm_import)) }
		},
		dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
