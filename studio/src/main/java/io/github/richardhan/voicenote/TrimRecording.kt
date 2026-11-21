package io.github.richardhan.voicenote

import android.media.*
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
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

private fun clock(ms: Int) = String.format(Locale.ROOT, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10)

@Composable
internal fun TrimAction(file: File, refresh: () -> Unit) {
	if (file.extension != "m4a") return
	var open by remember { mutableStateOf(false) }
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.trim)) }
	if (!open) return
	val scope = rememberCoroutineScope()
	var duration by remember { mutableIntStateOf(0) }
	var range by remember { mutableStateOf(0f..1f) }
	var player by remember { mutableStateOf<MediaPlayer?>(null) }
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
	DisposableEffect(Unit) { onDispose { player?.release() } }
	val start = (range.start * duration).toInt()
	val end = (range.endInclusive * duration).toInt()
	LaunchedEffect(player) {
		val playback = player ?: return@LaunchedEffect
		while (playback.isPlaying && playback.currentPosition < end) delay(30)
		if (player === playback) { player = null; playback.release() }
	}
	AlertDialog(onDismissRequest = { if (!saving) open = false }, title = { Text(stringResource(R.string.trim)) },
		text = { Column {
			Text("${clock(start)} – ${clock(end)}")
			RangeSlider(value = range, onValueChange = { range = it }, enabled = duration > 0 && player == null && !saving)
			TextButton(enabled = end > start && player == null && !saving, onClick = {
				val playback = MediaPlayer()
				try {
					playback.setDataSource(file.path)
					playback.prepare()
					playback.seekTo(start.toLong(), MediaPlayer.SEEK_CLOSEST)
					playback.start()
					player = playback
				} catch (_: Exception) { playback.release(); error = true }
			}) { Text(stringResource(R.string.preview_clip)) }
			if (error) Text(stringResource(R.string.clip_error))
		} }, confirmButton = {
			TextButton(enabled = end > start && !saving && player == null, onClick = {
				saving = true
				scope.launch {
					try { withContext(Dispatchers.IO) { saveClip(file, start, end) }; refresh(); open = false }
					catch (_: Exception) { error = true }
					finally { saving = false }
				}
			}) { Text(stringResource(R.string.save_clip)) }
		}, dismissButton = { TextButton(enabled = !saving, onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
}
