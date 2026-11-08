package com.eva.cloudtranscribe.audio

import de.sciss.jump3r.mp3.BRHist
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.ByteArrayOutputStream

/**
 * CBR MP3 encoder (16 kHz mono 64 kbps by default) on top of jump3r's low-level LAME port.
 * Uses only `de.sciss.jump3r.mp3/mpg`, which do not depend on javax.sound (unavailable on Android).
 */
object Mp3Encoder {
	fun encode(pcm: ShortArray, sampleRate: Int = 16_000, bitrateKbps: Int = 64): ByteArray {
		val lame = Lame()
		val gaud = GetAudio(); val ga = GainAnalysis(); val bs = BitStream(); val p = Presets()
		val qupvt = QuantizePVT(); val qu = Quantize(); val vbr = VBRTag(); val ver = Version()
		val id3 = ID3Tag(); val rv = Reservoir(); val tak = Takehiro(); val parse = Parse()
		@Suppress("UNUSED_VARIABLE") val hist = BRHist()
		val mpg = MPGLib(); val intf = Interface(); val common = Common()
		lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
		bs.setModules(ga, mpg, ver, vbr)
		id3.setModules(bs, ver)
		p.setModules(lame)
		qu.setModules(bs, rv, qupvt, tak)
		qupvt.setModules(tak, rv, lame.enc.psy)
		rv.setModules(bs)
		tak.setModules(qupvt)
		vbr.setModules(lame, bs, ver)
		gaud.setModules(parse, mpg)
		parse.setModules(ver, id3, p)
		mpg.setModules(intf, common)
		intf.setModules(vbr, common)

		val gfp = lame.lame_init()
		gfp.num_channels = 1
		gfp.in_samplerate = sampleRate
		gfp.out_samplerate = sampleRate
		gfp.mode = MPEGMode.MONO
		gfp.brate = bitrateKbps
		gfp.quality = 5
		id3.id3tag_init(gfp)
		gfp.write_id3tag_automatic = false
		check(lame.lame_init_params(gfp) >= 0) { "LAME init failed" }

		val out = ByteArrayOutputStream(pcm.size / 4 + 4096)
		val frame = 1152 * 8
		val buffer = ByteArray(frame * 2 + 7200)
		val left = IntArray(frame)
		val right = IntArray(frame)
		var offset = 0
		while (offset < pcm.size) {
			val n = minOf(frame, pcm.size - offset)
			for (i in 0 until n) { val v = pcm[offset + i].toInt() shl 16; left[i] = v; right[i] = v }
			val written = lame.lame_encode_buffer_int(gfp, left, right, n, buffer, 0, buffer.size)
			check(written >= 0) { "LAME encode failed: $written" }
			out.write(buffer, 0, written)
			offset += n
		}
		val flushed = lame.lame_encode_flush(gfp, buffer, 0, buffer.size)
		if (flushed > 0) out.write(buffer, 0, flushed)
		lame.lame_close(gfp)
		return out.toByteArray()
	}
}
