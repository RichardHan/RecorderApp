package io.github.richardhan.voicenote

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RecordingTooLong : Exception()
private const val RATE = 16000
private const val MAX_SECONDS = 600

/** Downmix PCM frames and average over each output sampling interval across codec buffers. */
internal class MonoSamples(private val onChunk: ((FloatArray) -> Unit)? = null) {
	private val chunks = mutableListOf<FloatArray>()
	private var count = 0
	private var phase = 0L
	private var sum = 0f
	private var frames = 0

	fun append(buffer: ByteBuffer, channels: Int, inputRate: Int, floating: Boolean) {
		require(channels > 0 && inputRate > 0)
		buffer.order(ByteOrder.LITTLE_ENDIAN)
		val frameBytes = channels * if (floating) 4 else 2
		while (buffer.remaining() >= frameBytes) {
			var frame = 0f
			repeat(channels) { frame += if (floating) buffer.float else buffer.short / 32768f }
			frame /= channels
			sum += frame
			frames++
			phase += RATE
			if (phase < inputRate) continue
			val amplitude = sum / frames
			while (phase >= inputRate) {
				if (onChunk != null && count == RATE * 20) {
					onChunk(finish())
					chunks.clear()
					count = 0
				}
				if (count >= RATE * MAX_SECONDS) throw RecordingTooLong()
				if (count % RATE == 0) chunks.add(FloatArray(RATE))
				chunks.last()[count % RATE] = amplitude
				count++
				phase -= inputRate
			}
			frames = 0
			sum = 0f
		}
	}

	fun finish(): FloatArray {
		require(count > 0)
		return FloatArray(count).also { result ->
			chunks.forEachIndexed { index, chunk ->
				val offset = index * RATE
				chunk.copyInto(result, offset, 0, minOf(RATE, count - offset))
			}
		}
	}
}

private fun MonoSamples.append(buffer: ByteBuffer, format: MediaFormat) {
	val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
		format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
	require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
	append(buffer, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
		encoding == AudioFormat.ENCODING_PCM_FLOAT)
}

internal suspend fun decodeForSpeakers(file: File, onChunk: ((FloatArray) -> Unit)? = null): FloatArray {
	val extractor = MediaExtractor()
	try {
		extractor.setDataSource(file.path)
		val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
		val format = extractor.getTrackFormat(track)
		if (onChunk == null && format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > MAX_SECONDS * 1000000L)
			throw RecordingTooLong()
		extractor.selectTrack(track)
		val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
		val samples = MonoSamples(onChunk)
		if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
			var data = ByteBuffer.allocateDirect(65536)
			while (extractor.sampleSize >= 0) {
				currentCoroutineContext().ensureActive()
				val bytes = extractor.sampleSize
				require(bytes <= 8 * 1024 * 1024)
				if (bytes > data.capacity()) data = ByteBuffer.allocateDirect(bytes.toInt())
				data.clear()
				val size = extractor.readSampleData(data, 0)
				if (size < 0) break
				data.position(0).limit(size)
				samples.append(data, format)
				if (!extractor.advance()) break
			}
		} else {
			val decoder = MediaCodec.createDecoderByType(mime)
			try {
				decoder.configure(format, null, null, 0)
				decoder.start()
				val info = MediaCodec.BufferInfo()
				var inputEnded = false
				while (true) {
					currentCoroutineContext().ensureActive()
					val input = if (inputEnded) -1 else decoder.dequeueInputBuffer(10000)
					if (input >= 0) {
						val data = requireNotNull(decoder.getInputBuffer(input))
						val size = extractor.readSampleData(data, 0)
						inputEnded = size < 0
						decoder.queueInputBuffer(input, 0, size.coerceAtLeast(0), extractor.sampleTime.coerceAtLeast(0),
							if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
						if (!inputEnded) extractor.advance()
					}
					val output = decoder.dequeueOutputBuffer(info, 10000)
					if (output < 0) continue
					if (info.size > 0) {
						val data = requireNotNull(decoder.getOutputBuffer(output))
						data.position(info.offset).limit(info.offset + info.size)
						samples.append(data, decoder.getOutputFormat(output))
					}
					decoder.releaseOutputBuffer(output, false)
					if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
				}
			} finally { decoder.release() }
		}
		return samples.finish()
	} finally { extractor.release() }
}
