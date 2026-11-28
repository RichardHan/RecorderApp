package io.github.richardhan.voicenote

import de.sciss.jump3r.mp3.*
import de.sciss.jump3r.mpg.MPGLib
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal fun cloudMp3(samples: FloatArray): ByteArray {
	val engine = Lame()
	val gain = GainAnalysis()
	val bits = BitStream()
	val presets = Presets()
	val quantizer = Quantize()
	val state = QuantizePVT()
	val tag = VBRTag()
	val version = Version()
	val id3 = ID3Tag()
	val reservoir = Reservoir()
	val huffman = Takehiro()
	val decoder = MPGLib()
	engine.setModules(gain, bits, presets, state, quantizer, tag, version, id3, decoder)
	bits.setModules(gain, decoder, version, tag)
	presets.setModules(engine)
	quantizer.setModules(bits, reservoir, state, huffman)
	state.setModules(huffman, reservoir, engine.enc.psy)
	reservoir.setModules(bits)
	huffman.setModules(state)
	tag.setModules(engine, bits, version)
	id3.setModules(bits, version)
	val flags = engine.lame_init()
	try {
		flags.in_samplerate = 16000
		flags.out_samplerate = 16000
		flags.num_channels = 1
		flags.mode = MPEGMode.MONO
		flags.brate = 64
		flags.quality = 3
		flags.bWriteVbrTag = false
		flags.write_id3tag_automatic = false
		check(engine.lame_init_params(flags) == 0)
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(16384)
		val pcm = IntArray(4096)
		var offset = 0
		while (offset < samples.size) {
			val n = minOf(samples.size - offset, pcm.size)
			for (i in 0 until n) pcm[i] = (samples[offset + i].coerceIn(-1f, 1f) * 32767).roundToInt() shl 16
			val bytes = engine.lame_encode_buffer_int(flags, pcm, pcm, n, buffer, 0, buffer.size)
			check(bytes >= 0)
			out.write(buffer, 0, bytes)
			offset += n
		}
		val tail = engine.lame_encode_flush(flags, buffer, 0, buffer.size)
		check(tail >= 0)
		out.write(buffer, 0, tail)
		return out.toByteArray()
	} finally { engine.lame_close(flags) }
}
