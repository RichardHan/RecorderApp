package io.github.richardhan.voicenote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

internal suspend fun waveform(file: File, duration: Int, cache: File): ByteArray {
	require(duration > 0)
	val count = 96
	val key = transcriptDigest(file.name + "/" + file.length() + "/" + file.lastModified()).toHexString()
	val target = File(cache, "wave-$key.bin")
	if (target.length() == count.toLong()) return target.readBytes()
	val peaks = FloatArray(count)
	var offset = 0L
	val total = duration * 16L
	fun append(samples: FloatArray) {
		for (amplitude in samples) {
			val index = minOf(count - 1, (offset * count / total).toInt())
			peaks[index] = maxOf(peaks[index], abs(amplitude))
			offset++
		}
	}
	append(decodeForSpeakers(file, ::append))
	val bytes = ByteArray(count) { (sqrt(peaks[it]).coerceIn(0f, 1f) * 127).toInt().toByte() }
	val temp = File.createTempFile("waveform-", ".part", cache)
	try { temp.writeBytes(bytes); temp.renameTo(target) } finally { temp.delete() }
	return bytes
}

@Composable
internal fun PlaybackWaveform(file: File, duration: Int, position: Long) {
	if (duration <= 0) return
	val cache = LocalContext.current.cacheDir
	val peaks by produceState<ByteArray?>(null, file, duration) {
		try { value = withContext(Dispatchers.IO) { waveform(file, duration, cache) } }
		catch (e: CancellationException) { throw e }
		catch (_: Exception) { value = null }
	}
	val values = peaks ?: return
	val accent = MaterialTheme.colorScheme.primary
	val remaining = MaterialTheme.colorScheme.outlineVariant
	Canvas(Modifier.fillMaxWidth().height(42.dp)) {
		val mid = size.height / 2
		val step = size.width / values.size
		for ((i, amplitude) in values.withIndex()) {
			val x = step * (i + 0.5f)
			val h = maxOf(1f, mid * amplitude / 127f)
			drawLine(if (i * duration.toLong() <= position * values.size) accent else remaining,
				Offset(x, mid - h), Offset(x, mid + h), step * 0.65f)
		}
	}
}
