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

private fun MediaExtractor.selectAacTrack(): MediaFormat? {
	for (track in 0 until trackCount) {
		val format = getTrackFormat(track)
		if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC) {
			selectTrack(track)
			return format
		}
	}
	return null
}

internal fun aacTrackDurationUs(file: File): Long? {
	val extractor = MediaExtractor()
	return try {
		extractor.setDataSource(file.path)
		extractor.selectAacTrack()?.getLong(MediaFormat.KEY_DURATION)
	} finally { extractor.release() }
}

internal suspend fun saveClip(file: File, spans: List<ClipSpan>): File {
	require(spans.isNotEmpty())
	val temp = File.createTempFile("clip-", ".part", file.parentFile)
	val extractor = MediaExtractor()
	try {
		extractor.setDataSource(file.path)
		val format = extractor.selectAacTrack()
		val extension = if (format == null) "wav" else "m4a"
		val target = File(file.parentFile, "${System.currentTimeMillis()}-clip-${UUID.randomUUID()}.$extension")
		if (format == null) {
			writePcmClip(file, temp, spans)
			check(temp.renameTo(target))
			return target
		}
		val muxer = MediaMuxer(temp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
		try {
			val output = muxer.addTrack(format)
			muxer.start()
			val data = ByteBuffer.allocateDirect(65536)
			val info = MediaCodec.BufferInfo()
			var offset = 0L
			var wrote = false
			for (span in spans) {
				extractor.seekTo(span.start * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
				val start = span.start * 1000L
				val end = span.end * 1000L
				while (extractor.sampleTime in 0 until end) {
					val time = extractor.sampleTime
					if (time >= start) {
						require(extractor.sampleSize <= data.capacity())
						data.clear()
						val bytes = extractor.readSampleData(data, 0)
						if (bytes <= 0) break
						info.set(0, bytes, offset + time - start, 0)
						muxer.writeSampleData(output, data, info)
						wrote = true
					}
					if (!extractor.advance()) break
				}
				offset += end - start
			}
			require(wrote)
			muxer.stop()
		} finally { muxer.release() }
		check(temp.renameTo(target))
		return target
	} finally { extractor.release(); temp.delete() }
}

@Composable
internal fun TrimAction(file: File, library: RecordingLibrary) {
	if (file.extension !in audioTypes) return
	var open by rememberSaveable { mutableStateOf(false) }
	TextButton(enabled = library.started == 0L, onClick = {
		library.stopPlayback()
		open = true
	}) { Text(stringResource(R.string.trim)) }
	if (!open) return
	val scope = rememberCoroutineScope()
	var duration by remember { mutableIntStateOf(0) }
	var startText by rememberSaveable { mutableStateOf("0:00") }
	var endInput by rememberSaveable { mutableStateOf<String?>(null) }
	var previewing by remember { mutableStateOf(false) }
	var error by remember { mutableIntStateOf(0) }
	var saving by remember { mutableStateOf(false) }
	LaunchedEffect(file) {
		try {
			val durationUs = withContext(Dispatchers.IO) {
				val meta = MediaMetadataRetriever()
				try {
					meta.setDataSource(file.path)
					meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000L)
				} finally { meta.release() }
			}
			when {
				durationUs == null -> error = R.string.clip_error
				durationUs !in 1L..Int.MAX_VALUE * 1000L -> error = R.string.clip_error
				else -> duration = ((durationUs + 999) / 1000).toInt()
			}
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			error = R.string.clip_error
		}
	}

	val endText = endInput ?: formatClipTime(duration)
	val start = parseClipTime(startText)
	val end = parseClipTime(endText)
	val valid = duration > 0 && start != null && end != null && start < end && end <= duration
	val editable = duration > 0 && !previewing && !saving
	val sliderStart = (start ?: 0).coerceAtMost(duration)
	val sliderEnd = (end ?: duration).coerceIn(sliderStart, duration)

	var history by rememberSaveable(duration) { mutableStateOf(arrayListOf("0:$duration")) }
	var step by rememberSaveable(duration) { mutableIntStateOf(0) }
	val spans = if (duration == 0) emptyList() else history[step].split(",").map {
		ClipSpan(it.substringBefore(':').toInt(), it.substringAfter(':').toInt())
	}
	fun applySelection(remove: Boolean) {
		if (start == null || end == null || !valid) return
		val next = selectClip(spans, ClipSpan(start, end), remove)
		if (next.isEmpty() || next == spans) return
		history = ArrayList(history.take(step + 1) + next.joinToString(",") { "${it.start}:${it.end}" })
		step++
	}

	if (previewing && start != null && end != null) {
		AudioRangePreview(file, start.toLong(), end.toLong(),
			onStop = { previewing = false },
			onError = { error = R.string.clip_error; previewing = false })
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
						error = 0
					}
				)
				OutlinedTextField(value = startText, onValueChange = { startText = it; error = 0 },
					label = { Text(stringResource(R.string.clip_start)) }, enabled = editable, singleLine = true)
				OutlinedTextField(value = endText, onValueChange = { endInput = it; error = 0 },
					label = { Text(stringResource(R.string.clip_end)) }, enabled = editable, singleLine = true)
				if (duration > 0) {
					Text(stringResource(R.string.clip_time_hint, formatClipTime(duration)), style = MaterialTheme.typography.bodySmall)
					if (valid) Text(stringResource(R.string.clip_length, formatClipTime(end - start)))
					else Text(stringResource(R.string.clip_invalid_range), color = MaterialTheme.colorScheme.error)
				}
				TextButton(enabled = valid && !saving, onClick = { error = 0; previewing = !previewing }) {
					Text(stringResource(if (previewing) R.string.stop_preview else R.string.preview_clip))
				}
				if (valid) {
					val selection = ClipSpan(start, end)
					for (remove in listOf(false, true)) {
						val next = selectClip(spans, selection, remove)
						TextButton(enabled = editable && next.isNotEmpty() && next != spans, onClick = { applySelection(remove) }) {
							Text(stringResource(if (remove) R.string.cut_range else R.string.keep_range))
						}
					}
				}
				androidx.compose.foundation.layout.Row {
					TextButton(enabled = editable && step > 0, onClick = { step-- }) { Text(stringResource(R.string.undo)) }
					TextButton(enabled = editable && step + 1 < history.size, onClick = { step++ }) { Text(stringResource(R.string.redo)) }
				}
				if (step > 0) Text(spans.joinToString(" · ") { formatClipTime(it.start) + "–" + formatClipTime(it.end) })
				if (file.extension != "m4a") Text(stringResource(R.string.clip_pcm_note))

				if (error != 0) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
			}
		},
		confirmButton = {
			TextButton(enabled = valid && editable, onClick = {
				if (start == null || end == null) return@TextButton
				saving = true
				scope.launch {
					try {
						val output = if (step == 0) listOf(ClipSpan(start, end)) else spans
						withContext(Dispatchers.IO) { saveClip(file, output) }
						library.refresh()
						open = false
					} catch (e: CancellationException) {
						throw e
					} catch (_: Exception) {
						error = R.string.clip_error
					} finally {
						saving = false
					}
				}
			}) { Text(stringResource(R.string.save_clip)) }
		},
		dismissButton = { TextButton(enabled = !saving, onClick = { open = false }) { Text(stringResource(R.string.cancel)) } }
	)
}
