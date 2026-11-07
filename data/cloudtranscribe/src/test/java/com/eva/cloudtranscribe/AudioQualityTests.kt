package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.audio.LinearResampler
import com.eva.cloudtranscribe.audio.Mp3Encoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AudioQualityTests {
	private fun zeroCrossings(x: ShortArray) = (1 until x.size).count { (x[it - 1] < 0) != (x[it] < 0) }

	@Test fun resampler48kTo16kKeepsFrequencyAndLength() {
		val src = ShortArray(48_000) { (sin(2 * PI * 440 * it / 48_000) * 10_000).toInt().toShort() }
		val r = LinearResampler(48_000, 16_000)
		// feed in odd-sized blocks like MediaCodec would
		val out = ArrayList<Short>()
		var i = 0
		while (i < src.size) { val n = minOf(1777, src.size - i); out += r.process(src.copyOfRange(i, i + n)).toList(); i += n }
		assertTrue(abs(out.size - 16_000) <= 2)
		assertEquals(880.0, zeroCrossings(out.toShortArray()).toDouble(), 6.0)
	}

	/** Writes a real-speech MP3 for external decode/ASR verification (box-side only). */
	@Test fun encodeRealSpeechForRoundTrip() {
		val wav = System.getenv("MP3_ROUNDTRIP_WAV"); val out = System.getenv("MP3_ROUNDTRIP_OUT")
		assumeTrue(wav != null && out != null)
		val bytes = File(wav!!).readBytes()
		val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		var pos = 12; var pcm = ShortArray(0)
		while (pos < bytes.size - 8) {
			val id = String(bytes, pos, 4); val size = bb.getInt(pos + 4)
			if (id == "data") { pcm = ShortArray(size / 2) { bb.getShort(pos + 8 + it * 2) }; break }
			pos += 8 + size
		}
		File(out!!).writeBytes(Mp3Encoder.encode(pcm))
	}
}
