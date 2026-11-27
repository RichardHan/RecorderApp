package io.github.richardhan.voicenote

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class ClipSpan(val start: Int, val end: Int) {
	init { require(start >= 0 && end > start) }
}

internal fun selectClip(spans: List<ClipSpan>, selection: ClipSpan, remove: Boolean): List<ClipSpan> = buildList {
	for (span in spans) {
		val start = maxOf(span.start, selection.start)
		val end = minOf(span.end, selection.end)
		if (remove) {
			if (start >= end) add(span) else {
				if (span.start < start) add(ClipSpan(span.start, start))
				if (end < span.end) add(ClipSpan(end, span.end))
			}
		} else if (start < end) add(ClipSpan(start, end))
	}
}

internal fun writeWaveHeader(output: RandomAccessFile, dataBytes: Long) {
	require(dataBytes in 0..0xffffffffL - 36)
	val bytes = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
	bytes.put("RIFF".toByteArray()).putInt((dataBytes + 36).toInt()).put("WAVEfmt ".toByteArray()).putInt(16)
	bytes.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
	bytes.put("data".toByteArray()).putInt(dataBytes.toInt())
	output.seek(0)
	output.write(bytes.array())
}

internal suspend fun writePcmClip(source: File, target: File, spans: List<ClipSpan>) {
	RandomAccessFile(target, "rw").use { output ->
		output.setLength(0)
		output.seek(44)
		var offset = 0L
		var spanIndex = 0
		fun write(samples: FloatArray) {
			val end = offset + samples.size
			val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
			while (spanIndex < spans.size) {
				val span = spans[spanIndex]
				val first = maxOf(offset, span.start * 16L)
				val last = minOf(end, span.end * 16L)
				for (i in first until last) buffer.putShort((samples[(i - offset).toInt()] * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
				if (span.end * 16L > end) break
				spanIndex++
			}
			output.write(buffer.array(), 0, buffer.position())
			offset = end
		}
		write(decodeForSpeakers(source, ::write))
		val size = output.length() - 44
		require(size > 0)
		writeWaveHeader(output, size)
	}
}
