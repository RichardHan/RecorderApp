package io.github.richardhan.voicenote

import android.media.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import java.util.UUID
import kotlinx.coroutines.*

private fun saveClip(file: File, startMs: Int, endMs: Int) {
	require(endMs > startMs)
	val temp = File.createTempFile("clip-", ".part", file.parentFile)
	val extractor = MediaExtractor()
	try {
		extractor.setDataSource(file.path)
		val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
		val format = extractor.getTrackFormat(track)
		extractor.selectTrack(track)
		extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
		val muxer = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
		try {
			val output = muxer.addTrack(format)
			muxer.start()
			val data = ByteBuffer.allocateDirect(65536)
			val info = MediaCodec.BufferInfo()
			var first = -1L
			while (extractor.sampleTime in 0 until endMs * 1000L) {
				val time = extractor.sampleTime
				if (time >= startMs * 1000L) {
					require(extractor.sampleSize <= data.capacity())
					data.clear()
					val bytes = extractor.readSampleData(data, 0)
					if (bytes <= 0) break
					if (first < 0) first = time
					info.set(0, bytes, time - first, 0)
					muxer.writeSampleData(output, data, info)
				}
				if (!extractor.advance()) break
			}
			require(first >= 0)
			muxer.stop()
		} finally { muxer.release() }
		check(temp.renameTo(File(file.parentFile, "${System.currentTimeMillis()}-clip-${UUID.randomUUID()}.m4a")))
	} finally { extractor.release(); temp.delete() }
}

@Composable
internal fun TrimAction(file: File, refresh: () -> Unit) {
	if (file.extension != "m4a") return
	var open by rememberSaveable { mutableStateOf(false) }
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.trim)) }
	if (!open) return
	val scope = rememberCoroutineScope()
	var duration by remember { mutableIntStateOf(0) }
	var startText by rememberSaveable { mutableStateOf("0:00") }
	var endInput by rememberSaveable { mutableStateOf<String?>(null) }
	var previewing by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf(false) }
	var saving by remember { mutableStateOf(false) }
	LaunchedEffect(file) {
		duration = withContext(Dispatchers.IO) {
			val meta = MediaMetadataRetriever()
			try { meta.setDataSource(file.path); meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0 }
			catch (_: Exception) { 0 } finally { meta.release() }
		}
		error = duration <= 0
	}

	val endText = endInput ?: formatClipTime(duration)
	val start = parseClipTime(startText)
	val end = parseClipTime(endText)
	val valid = duration > 0 && start != null && end != null && start < end && end <= duration
	val editable = duration > 0 && !previewing && !saving
	val sliderStart = (start ?: 0).coerceAtMost(duration)
	val sliderEnd = (end ?: duration).coerceIn(sliderStart, duration)

	if (previewing && start != null && end != null) {
		AudioRangePreview(file, start.toLong(), end.toLong(),
			onStop = { previewing = false }, onError = { error = true; previewing = false })
	}

	AlertDialog(
		onDismissRequest = { if (!saving) open = false },
		title = { Text(stringResource(R.string.trim)) },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				RangeSlider(
					value = sliderStart.toFloat()..sliderEnd.toFloat(),
					valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
					enabled = editable,
					onValueChange = { range ->
						startText = formatClipTime(range.start.roundToInt().coerceAtMost(duration))
						endInput = formatClipTime(range.endInclusive.roundToInt().coerceAtMost(duration))
						error = false
					}
				)
				OutlinedTextField(value = startText, onValueChange = { startText = it; error = false },
					label = { Text(stringResource(R.string.clip_start)) }, enabled = editable, singleLine = true)
				OutlinedTextField(value = endText, onValueChange = { endInput = it; error = false },
					label = { Text(stringResource(R.string.clip_end)) }, enabled = editable, singleLine = true)
				Text(stringResource(R.string.clip_time_hint, formatClipTime(duration)), style = MaterialTheme.typography.bodySmall)
				if (duration > 0) {
					if (valid) Text(stringResource(R.string.clip_length, formatClipTime(end - start)))
					else Text(stringResource(R.string.clip_invalid_range), color = MaterialTheme.colorScheme.error)
				}
				TextButton(enabled = valid && !saving, onClick = { error = false; previewing = !previewing }) {
					Text(stringResource(if (previewing) R.string.stop_preview else R.string.preview_clip))
				}
				if (error) Text(stringResource(R.string.clip_error), color = MaterialTheme.colorScheme.error)
			}
		},
		confirmButton = {
			TextButton(enabled = valid && editable, onClick = {
				if (start == null || end == null) return@TextButton
				saving = true
				scope.launch {
					try {
						withContext(Dispatchers.IO) { saveClip(file, start, end) }
						refresh()
						open = false
					} catch (e: CancellationException) {
						throw e
					} catch (_: Exception) {
						error = true
					} finally {
						saving = false
					}
				}
			}) { Text(stringResource(R.string.save_clip)) }
		},
		dismissButton = { TextButton(enabled = !saving, onClick = { open = false }) { Text(stringResource(R.string.cancel)) } }
	)
}
