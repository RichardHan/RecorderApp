package io.github.richardhan.voicenote

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

internal enum class DocumentFormat(val label: String, val ext: String, val mime: String, val timed: Boolean = false) {
	Text("TXT", "txt", "text/plain"), Markdown("Markdown", "md", "text/markdown"),
	SubRip("SRT", "srt", "application/x-subrip", true), WebVtt("WebVTT", "vtt", "text/vtt", true)
}

private fun subtitleTime(ms: Long, separator: Char) = String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d",
	ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, separator, ms % 1000)

internal fun subtitleText(segments: List<TimedText>, format: DocumentFormat): String {
	require(format.timed && segments.isNotEmpty())
	val vtt = format == DocumentFormat.WebVtt
	val separator = if (vtt) '.' else ','
	return buildString {
		if (vtt) append("WEBVTT\n\n")
		for ((index, segment) in segments.sortedBy { it.start }.withIndex()) {
			val start = (segment.start * 1000).roundToLong()
			val end = (segment.end * 1000).roundToLong().coerceAtLeast(start + 1)
			appendLine(index + 1)
			append(subtitleTime(start, separator)).append(" --> ").appendLine(subtitleTime(end, separator))
			val text = segment.text.lines().filter { it.isNotBlank() }.joinToString("\n")
			appendLine(if (vtt) text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") else text)
			appendLine()
		}
	}
}

@Composable
internal fun TranscriptDocuments(file: File, transcript: String) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var menu by remember { mutableStateOf(false) }
	var format by rememberSaveable { mutableStateOf(DocumentFormat.Text) }
	var failed by remember { mutableStateOf(false) }
	val segments by produceState(emptyList<TimedText>(), file, transcript) {
		value = withContext(Dispatchers.IO) {
			try { readTiming(file) } catch (_: java.io.IOException) { emptyList() }
			catch (_: org.json.JSONException) { emptyList() }
		}
	}
	val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		val uri = result.data?.data
		if (result.resultCode == Activity.RESULT_OK && uri != null) scope.launch {
			try {
				withContext(Dispatchers.IO) {
					val text = when (format) {
						DocumentFormat.Text -> transcript
						DocumentFormat.Markdown -> "# ${file.nameWithoutExtension}\n\n$transcript\n"
						else -> subtitleText(readTiming(file), format)
					}
					requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(text) }
				}
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { failed = true }
		}
	}
	androidx.compose.foundation.layout.Box {
		TextButton(onClick = { failed = false; menu = true }) { Text(stringResource(R.string.transcript_export)) }
		DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
			DocumentFormat.entries.forEach { option ->
				DropdownMenuItem(text = { Text(option.label) }, enabled = !option.timed || segments.isNotEmpty(), onClick = {
					format = option
					menu = false
					save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
						.setType(option.mime).putExtra(Intent.EXTRA_TITLE, "${file.nameWithoutExtension}.${option.ext}"))
				})
			}
		}
	}
	if (failed) Text(stringResource(R.string.document_error))
}
