package com.eva.cloudtranscribe.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.eva.cloudtranscribe.api.AudioPayload
import com.eva.cloudtranscribe.engine.AudioRangeSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

const val TARGET_RATE = 16_000

/** 16 kHz mono 16-bit little-endian PCM file; cut by time range and encoded to MP3 per chunk. */
class PcmFileSource(
	private val file: File,
	private val silenceRms: Double = 60.0,
	private val bitrateKbps: Int = 64,
) : AudioRangeSource {
	override val durationMs: Long = file.length() / 2 * 1000 / TARGET_RATE

	fun read(startMs: Long, endMs: Long): ShortArray {
		val startSample = startMs * TARGET_RATE / 1000
		val endSample = minOf(endMs * TARGET_RATE / 1000, file.length() / 2)
		val count = (endSample - startSample).coerceAtLeast(0).toInt()
		val bytes = ByteArray(count * 2)
		RandomAccessFile(file, "r").use { raf -> raf.seek(startSample * 2); raf.readFully(bytes) }
		val out = ShortArray(count)
		ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
		return out
	}

	/** Silent only if every 100 ms window is below the RMS threshold. */
	override fun isSilent(startMs: Long, endMs: Long): Boolean = isSilent(read(startMs, endMs), silenceRms)

	override fun encode(startMs: Long, endMs: Long) = AudioPayload(Mp3Encoder.encode(read(startMs, endMs), TARGET_RATE, bitrateKbps), "mp3")

	companion object {
		fun isSilent(samples: ShortArray, threshold: Double): Boolean {
			if (samples.isEmpty()) return true
			val window = TARGET_RATE / 10
			var i = 0
			while (i < samples.size) {
				val end = minOf(samples.size, i + window)
				var sum = 0.0
				for (j in i until end) { val v = samples[j].toDouble(); sum += v * v }
				if (sqrt(sum / (end - i)) >= threshold) return false
				i = end
			}
			return true
		}
	}
}

/** Decodes any platform-supported recording (m4a/aac, amr, ogg/opus, wav…) with MediaCodec into 16 kHz mono PCM. */
object PcmDecoder {
	suspend fun decodeToFile(context: Context, uri: Uri, out: File) {
		val extractor = MediaExtractor()
		extractor.setDataSource(context, uri, null)
		val track = (0 until extractor.trackCount).firstOrNull {
			extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
		} ?: throw IOException("No audio track")
		extractor.selectTrack(track)
		val format = extractor.getTrackFormat(track)
		val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
		codec.configure(format, null, null, 0)
		codec.start()
		var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
		var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
		val resampler = LinearResampler(srcRate, TARGET_RATE)
		val info = MediaCodec.BufferInfo()
		out.parentFile?.mkdirs()
		try {
			BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
				var inputDone = false
				var outputDone = false
				while (!outputDone) {
					currentCoroutineContext().ensureActive()
					if (!inputDone) {
						val inIndex = codec.dequeueInputBuffer(10_000)
						if (inIndex >= 0) {
							val buf = codec.getInputBuffer(inIndex)!!
							val size = extractor.readSampleData(buf, 0)
							if (size < 0) {
								codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
								inputDone = true
							} else {
								codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
								extractor.advance()
							}
						}
					}
					val outIndex = codec.dequeueOutputBuffer(info, 10_000)
					when {
						outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
							val f = codec.outputFormat
							srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
							channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
							resampler.setSourceRate(srcRate)
						}
						outIndex >= 0 -> {
							if (info.size > 0) {
								val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
								buf.position(info.offset); buf.limit(info.offset + info.size)
								val shorts = buf.asShortBuffer()
								val frames = shorts.remaining() / channels
								val mono = ShortArray(frames)
								for (i in 0 until frames) {
									var sum = 0
									for (c in 0 until channels) sum += shorts.get(i * channels + c)
									mono[i] = (sum / channels).toShort()
								}
								val res = resampler.process(mono)
								val bytes = ByteArray(res.size * 2)
								ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(res)
								stream.write(bytes)
							}
							codec.releaseOutputBuffer(outIndex, false)
							if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
						}
					}
				}
			}
		} finally {
			runCatching { codec.stop() }
			codec.release()
			extractor.release()
		}
	}
}

/** Streaming linear-interpolation resampler (adequate for speech at 16 kHz). */
class LinearResampler(private var src: Int, private val dst: Int) {
	private var pos = 0.0
	private var last: Short = 0
	fun setSourceRate(rate: Int) { src = rate }
	fun process(input: ShortArray): ShortArray {
		if (src == dst) return input
		val step = src.toDouble() / dst
		val out = ArrayList<Short>((input.size / step).toInt() + 2)
		while (pos < input.size) {
			val i = pos.toInt()
			val frac = pos - i
			val a = if (i == 0) last.toDouble() else input[i - 1].toDouble()
			val b = input[i].toDouble()
			out += (a + (b - a) * frac).toInt().toShort()
			pos += step
		}
		pos -= input.size
		if (input.isNotEmpty()) last = input.last()
		return out.toShortArray()
	}
}
