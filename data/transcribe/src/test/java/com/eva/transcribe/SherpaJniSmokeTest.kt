package com.eva.transcribe

import com.eva.transcribe.offline.OfflineEngines
import com.eva.transcribe.offline.OfflineSpeechEngine
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Linux x64 JNI smoke test of the exact Kotlin configs the app builds (not a phone test).
 * Runs only when SHERPA_LINUX_JNI_DIR and model dirs are provided.
 */
class SherpaJniSmokeTest {
	private fun wav(path: String): FloatArray {
		val bytes = File(path).readBytes()
		val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
		var pos = 12
		while (pos < bytes.size - 8) {
			val id = String(bytes, pos, 4); val size = bb.getInt(pos + 4)
			if (id == "data") {
				val n = size / 2
				return FloatArray(n) { bb.getShort(pos + 8 + it * 2) / 32768f }
			}
			pos += 8 + size
		}
		error("no data chunk")
	}

	private fun run(engine: String, envDir: String) {
		val dir = System.getenv(envDir)
		val wavPath = System.getenv("SHERPA_SMOKE_WAV")
		assumeTrue(System.getenv("SHERPA_LINUX_JNI_DIR") != null && dir != null && wavPath != null)
		val t = System.currentTimeMillis()
		val r = OfflineRecognizer(config = OfflineSpeechEngine.buildConfig(engine, File(dir!!), 2))
		val load = System.currentTimeMillis() - t
		val s = r.createStream()
		s.acceptWaveform(wav(wavPath!!), 16_000)
		val t2 = System.currentTimeMillis()
		r.decode(s)
		val text = r.getResult(s).text
		println("SMOKE $engine load=${load}ms decode=${System.currentTimeMillis() - t2}ms text=$text")
		s.release(); r.release()
		check(text.isNotBlank()) { "empty result" }
	}

	/** Qwen3-ASR 1.7B (community int8 export) with the app's exact config; prints per-clip timings, token counts and peak RSS. */
	@Test fun qwen3_1_7b() {
		val dir = System.getenv("SHERPA_SMOKE_QWEN17"); val wavs = System.getenv("SHERPA_SMOKE_WAVS")
		assumeTrue(System.getenv("SHERPA_LINUX_JNI_DIR") != null && dir != null && wavs != null)
		fun hwm() = File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }.trim()
		val t = System.currentTimeMillis()
		val r = OfflineRecognizer(config = OfflineSpeechEngine.buildConfig(OfflineEngines.QWEN3, File(dir!!), 2))
		println("SMOKE17 load=${System.currentTimeMillis() - t}ms ${hwm()}")
		for (path in wavs!!.split(',')) {
			val samples = wav(path)
			val s = r.createStream(); s.acceptWaveform(samples, 16_000)
			val t2 = System.currentTimeMillis(); r.decode(s)
			val res = r.getResult(s)
			println("SMOKE17 ${File(path).name} audio=${samples.size / 16}ms decode=${System.currentTimeMillis() - t2}ms tokens=${res.tokens.size} ${hwm()} text=${res.text}")
			s.release()
			check(res.text.isNotBlank()) { "empty result for $path" }
		}
		r.release()
	}

	@Test fun senseVoice() = run(OfflineEngines.SENSEVOICE, "SHERPA_SMOKE_SENSEVOICE")
	@Test fun qwen3() = run(OfflineEngines.QWEN3, "SHERPA_SMOKE_QWEN")
	@Test fun fireRedAed() = run(OfflineEngines.FIRERED_AED, "SHERPA_SMOKE_FIRERED")
}
