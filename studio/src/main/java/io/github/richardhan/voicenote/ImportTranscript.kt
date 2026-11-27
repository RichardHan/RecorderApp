package io.github.richardhan.voicenote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader

private fun readDocument(reader: Reader): String = buildString {
	val chunk = CharArray(8192)
	while (true) {
		val count = reader.read(chunk)
		if (count < 0) break
		require(length + count <= 8 * 1024 * 1024)
		append(chunk, 0, count)
	}
}.removePrefix("\uFEFF").replace("\r\n", "\n").trim().also { require(it.isNotBlank()) }

private val cue = Regex("""(?m)^(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})\s+-->\s+(?:(\d+):)?(\d{2}):(\d{2})[.,](\d{3})(?:[ \t]+[^\n]*)?$""")
internal fun importedTranscript(document: String): Pair<String, JSONArray?> {
	val segments = JSONArray()
	val words = mutableListOf<String>()
	val blocks = document.split(Regex("\n[\\t ]*\n"))
	val subtitle = document.startsWith("WEBVTT") || blocks.any { cue.containsMatchIn(it) }
	if (!subtitle) return document to null
	for (block in blocks) {
		val match = cue.find(block) ?: continue
		fun time(at: Int): Double {
			val groups = match.groupValues
			return (groups[at].toLongOrNull() ?: 0) * 3600.0 + groups[at + 1].toInt() * 60 + groups[at + 2].toInt() + groups[at + 3].toInt() / 1000.0
		}
		val start = time(1)
		val end = time(5)
		val line = block.substring(match.range.last + 1).trim().replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
		require(end > start && line.isNotBlank())
		segments.put(JSONObject().put("start", start).put("end", end).put("text", line))
		words.add(line)
	}
	require(words.isNotEmpty())
	return words.joinToString("\n") to segments
}

@Composable
internal fun ImportTranscript(file: File, speech: SpeechConnection) {
	val context = LocalContext.current
	val library = (context.applicationContext as StudioApplication).library
	val scope = rememberCoroutineScope()
	var confirm by remember { mutableStateOf(false) }
	var uri by remember { mutableStateOf<android.net.Uri?>(null) }
	fun save() {
		val source = uri ?: return
		scope.launch {
			try {
				withContext(Dispatchers.IO) {
					val text = requireNotNull(context.contentResolver.openInputStream(source)).bufferedReader(Charsets.UTF_8).use(::readDocument)
					val (words, segments) = importedTranscript(text)
					saveTranscription(file, words, null, segments)
				}
				library.transcriptChanged()
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { library.message = R.string.document_error }
		}
	}
	val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
		if (it != null) { uri = it; if (transcriptFile(file).exists()) confirm = true else save() }
	}
	TextButton(enabled = !speech.busy && file !in library.recognizing, onClick = { choose.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream")) }) {
		Text(stringResource(R.string.import_transcript))
	}
	if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = { Text(stringResource(R.string.import_replace)) },
		confirmButton = { TextButton(onClick = { confirm = false; save() }) { Text(stringResource(R.string.confirm_import)) } },
		dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
