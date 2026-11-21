package io.github.richardhan.voicenote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

internal fun Intent.sharedImportSource(): Uri? {
	if (action != Intent.ACTION_SEND) return null
	val payload = clipData
	if ((payload?.itemCount ?: 0) > 1) return null
	val item = payload?.takeIf { it.itemCount == 1 }?.getItemAt(0)
	val mime = type?.substringBefore(';')?.trim().orEmpty()
	return when {
		mime.startsWith("audio/", ignoreCase = true) -> {
			val uri = IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java) ?: item?.uri
			uri?.takeIf { it.scheme == "content" && !it.authority.isNullOrBlank() }
		}
		mime.equals("text/plain", ignoreCase = true) -> {
			val text = getCharSequenceExtra(Intent.EXTRA_TEXT) ?: item?.text
			text?.toString()?.let(::sharedHttpsLink)?.let(Uri::parse)
		}
		else -> null
	}
}

private fun Context.sharedFileName(uri: Uri): String? = try {
	contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { rows ->
		if (rows.moveToFirst()) rows.getString(0)?.take(200) else null
	}
} catch (_: RuntimeException) { null }

@Composable
internal fun SharedImportDialog(source: Uri, library: RecordingLibrary, dismiss: () -> Unit) {
	val audio = source.scheme == "content"
	val context = LocalContext.current
	val name by produceState<String?>(null, source) {
		if (audio) value = withContext(Dispatchers.IO) { context.sharedFileName(source) }
	}
	val details = if (audio) name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.shared_audio)
		else source.toString()
	AlertDialog(onDismissRequest = dismiss,
		title = { Text(stringResource(if (audio) R.string.import_file else R.string.import_url)) },
		text = { Text(details, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
		confirmButton = {
			TextButton(enabled = !library.importing, onClick = {
				if (audio) library.importSource(uri = source) else library.importSource(url = source.toString())
				dismiss()
			}) { Text(stringResource(R.string.confirm_import)) }
		},
		dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
