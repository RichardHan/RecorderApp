package io.github.richardhan.voicenote

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal fun nearestSegmentIndex(segments: List<TimedText>, ms: Long): Int? {
	val time = ms / 1000.0
	return segments.indices.minByOrNull { i -> maxOf(segments[i].start - time, time - segments[i].end, 0.0) }
}

@Composable
internal fun TranscriptTimeline(file: File, revision: Int, speech: SpeechConnection) {
	val context = LocalContext.current
	val library = (context.applicationContext as StudioApplication).library
	var open by remember { mutableStateOf(false) }
	val segments by produceState(emptyList<TimedText>(), file, revision) {
		value = withContext(Dispatchers.IO) { runCatching { readTiming(file) }.getOrDefault(emptyList()) }
	}
	if (segments.isEmpty()) return
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.timed_transcript)) }
	if (!open) return
	val grouped by produceState(emptyMap<Int?, List<File>>(), file, segments, library.photoRevision) {
		value = withContext(Dispatchers.IO) { photos(context, file).groupBy { nearestSegmentIndex(segments, photoTime(it)) } }
	}
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.timed_transcript)) }, text = {
		LazyColumn { itemsIndexed(segments) { index, line ->
			val ms = (line.start * 1000).toLong()
			TextButton(enabled = library.started == 0L, onClick = { context.playRecording(file, ms) }) { Text(formatClipTime(ms.toInt())) }
			var edit by remember(line) { mutableStateOf(false) }
			if (edit) {
				var draft by remember(line) { mutableStateOf(line.text) }
				OutlinedTextField(draft, { draft = it })
				TextButton(enabled = !speech.busy && draft.isNotBlank(), onClick = { speech.editTimed(file, segments, index, draft); edit = false }) { Text(stringResource(R.string.done)) }
			} else {
				Text(line.text)
				TextButton(onClick = { edit = true }) { Text(stringResource(R.string.meeting_edit)) }
			}
			FlowRow { for (picture in grouped[index].orEmpty()) {
				val thumb by produceState<android.graphics.Bitmap?>(null, picture) { value = withContext(Dispatchers.IO) {
					BitmapFactory.decodeFile(picture.path, BitmapFactory.Options().apply { inSampleSize = 8 })
				} }
				thumb?.let { Image(it.asImageBitmap(), library.catalog.value(file, "photo." + picture.name), Modifier.size(64.dp)) }
			} }
			HorizontalDivider()
		} }
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}
