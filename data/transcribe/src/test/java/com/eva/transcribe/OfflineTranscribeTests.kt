package com.eva.transcribe

import com.eva.transcribe.domain.models.checkpointIdentifier

import com.eva.transcribe.archive.SafeTarBz2Extractor
import com.eva.transcribe.data.models.ModelFileEntry
import com.eva.transcribe.data.models.ModelsMetadataList
import com.eva.transcribe.offline.LiveUtterancePipeline
import com.eva.transcribe.offline.UtteranceSegmenter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun tone(ms: Int, amp: Int, freq: Double = 300.0) = ShortArray(16 * ms) { (sin(2 * PI * freq * it / 16000) * amp).toInt().toShort() }
private fun noise(ms: Int, amp: Int) = ShortArray(16 * ms) { ((it * 7919 % 13) - 6).let { v -> v * amp / 6 }.toShort() }
private fun concat(vararg a: ShortArray) = a.fold(ShortArray(0)) { acc, x -> acc + x }

class SegmenterTests {
	@Test fun splitsPhrasesWithPaddingAndAccountsForEverySample() {
		val audio = concat(noise(1000, 20), tone(1500, 3000), noise(1500, 20), tone(800, 3000), noise(1000, 20))
		val seg = UtteranceSegmenter()
		val utts = ArrayList<UtteranceSegmenter.Utterance>()
		var i = 0
		while (i < audio.size) { val n = minOf(1234, audio.size - i); utts += seg.accept(audio.copyOfRange(i, i + n)); i += n }
		utts += seg.flush()
		assertEquals(2, utts.size)
		assertTrue("pre-roll kept", utts[0].startSample <= 16_000 - 16 * 270)
		assertTrue("post-roll kept", utts[0].samples.size >= 16 * (1500 + 500))
		assertEquals(audio.size.toLong(), utts.sumOf { it.samples.size.toLong() } + seg.silenceSamples)
	}

	@Test fun tailIsFlushedWhenStoppingMidSentence() {
		val seg = UtteranceSegmenter()
		assertTrue(seg.accept(concat(noise(500, 20), tone(1200, 3000))).isEmpty())
		val tail = seg.flush()
		assertEquals(1, tail.size)
		assertTrue(tail[0].samples.size >= 16 * 1200)
	}

	@Test fun quietSpeechIsKept() {
		val seg = UtteranceSegmenter()
		val out = seg.accept(concat(noise(1000, 10), tone(1000, 250), noise(1000, 10))) + seg.flush()
		assertEquals(1, out.size)
	}

	@Test fun longSpeechIsCappedForModelLimits() {
		val seg = UtteranceSegmenter(maxUtteranceMs = 5_000)
		val out = seg.accept(tone(12_000, 3000)) + seg.flush()
		assertTrue(out.size >= 3)
		assertTrue(out.all { it.samples.size <= 16 * 5_000 + 16 * 30 })
	}
}

class PipelineTests {
	@Test fun ordersResultsAndFlushesTail() = runBlocking {
		val p = LiveUtterancePipeline(this, queueCapacity = 8) { s -> "len${s.size / 1600}" }
		p.accept(concat(noise(500, 20), tone(1000, 3000), noise(1500, 20)), 16 * 3000)
		p.accept(tone(700, 3000), 16 * 700)
		assertTrue(p.finish())
		val text = p.text()
		assertEquals(2, text.lines().size)
		assertEquals(0L, p.droppedSamples)
	}

	@Test fun backlogIsVisibleNotSilent() = runBlocking {
		val p = LiveUtterancePipeline(this, queueCapacity = 1) { delay(300); "x" }
		repeat(6) { p.accept(concat(tone(600, 3000), noise(900, 20)), 16 * 1500) }
		assertTrue(p.finish())
		assertTrue(p.droppedSamples > 0)
		assertTrue(p.text().contains("辨識跟不上"))
	}

	@Test fun decoderFailureIsMarked() = runBlocking {
		val p = LiveUtterancePipeline(this) { error("boom") }
		p.accept(concat(tone(800, 3000), noise(900, 20)), 16 * 1700)
		p.finish()
		assertEquals(1, p.failedUtterances)
		assertTrue(p.text().contains("辨識失敗"))
	}
}

class ExtractorTests {
	private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

	private fun tar(build: TarArchiveOutputStream.() -> Unit): ByteArray {
		val bos = ByteArrayOutputStream()
		TarArchiveOutputStream(BZip2CompressorOutputStream(bos)).use { it.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX); it.build() }
		return bos.toByteArray()
	}

	private fun TarArchiveOutputStream.file(name: String, data: ByteArray) {
		putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() }); write(data); closeArchiveEntry()
	}

	private val a = "model".toByteArray()
	private val b = "tokens".toByteArray()
	private val manifest = listOf(ModelFileEntry("model.onnx", a.size.toLong(), sha(a)), ModelFileEntry("sub/tokens.txt", b.size.toLong(), sha(b)))

	@Test fun extractsOnlyManifestFilesAndStripsRoot() {
		val data = tar { file("root/model.onnx", a); file("root/sub/tokens.txt", b); file("root/test_wavs/x.wav", ByteArray(100)) }
		val dir = Files.createTempDirectory("x").toFile()
		SafeTarBz2Extractor.extract(ByteArrayInputStream(data), dir, manifest)
		assertEquals("model", File(dir, "model.onnx").readText())
		assertFalse(File(dir, "test_wavs").exists())
	}

	@Test fun rejectsTraversal() {
		val data = tar { file("root/../../evil", a) }
		try { SafeTarBz2Extractor.extract(ByteArrayInputStream(data), Files.createTempDirectory("x").toFile(), manifest); fail() } catch (_: SecurityException) {}
	}

	@Test fun ignoresSymlinksAndFailsOnMissing() {
		val data = tar {
			putArchiveEntry(TarArchiveEntry("root/model.onnx", TarArchiveEntry.LF_SYMLINK).apply { linkName = "/etc/passwd" }); closeArchiveEntry()
			file("root/sub/tokens.txt", b)
		}
		val dir = Files.createTempDirectory("x").toFile()
		try { SafeTarBz2Extractor.extract(ByteArrayInputStream(data), dir, manifest); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("missing")) }
		assertFalse(File(dir, "model.onnx").exists())
	}

	@Test fun rejectsChecksumMismatch() {
		val data = tar { file("root/model.onnx", "MODEL".toByteArray()); file("root/sub/tokens.txt", b) }
		try { SafeTarBz2Extractor.extract(ByteArrayInputStream(data), Files.createTempDirectory("x").toFile(), manifest); fail() } catch (_: IOException) {}
	}
}

class CatalogueTests {
	private fun catalogue(): ModelsMetadataList {
		val f = listOf(File("src/main/assets/vosk_models_metadata.json"), File("data/transcribe/src/main/assets/vosk_models_metadata.json")).first { it.exists() }
		return Json { ignoreUnknownKeys = true }.decodeFromString(ModelsMetadataList.serializer(), f.readText())
	}

	@Test fun exactCheckpointNamesAndRetiredIndianEnglish() {
		val models = catalogue().models
		assertFalse(models.any { it.locale == "en-IN" })
		assertTrue(models.any { it.locale == "en-US" })
		models.forEach { m ->
			val model = com.eva.transcribe.domain.models.STTModel(modelId = m.modelId, name = m.name,
				displayName = m.displayName, downloadURI = m.modelUri, archiveFormat = m.archiveFormat)
			val id = model.checkpointIdentifier()
			assertTrue(id.isNotBlank() && id != "Vosk")
			if (m.archiveFormat == "zip") assertEquals(m.modelUri.substringAfterLast('/').removeSuffix(".zip"), id)
			if (m.modelId == "vosk-en-us-small") assertEquals("vosk-model-small-en-us-0.15", id)
		}
	}

	@Test fun bundledCatalogueIsValid() {
		val list = catalogue()
		val sherpa = list.models.filter { it.engine.startsWith("sherpa-") }
		assertEquals(setOf("sherpa-sensevoice", "sherpa-qwen3-asr", "sherpa-firered-aed"), sherpa.map { it.engine }.toSet())
		assertEquals(4, sherpa.size)
		assertTrue(list.models.any { it.engine == "vosk" })
		assertEquals("model ids are unique", list.models.size, list.models.map { it.modelId }.toSet().size)
		sherpa.filter { it.archiveFormat == "tar.bz2" }.forEach { m ->
			assertTrue(m.modelUri.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"))
			assertTrue(Regex("[0-9a-f]{64}").matches(m.checksum!!))
		}
		sherpa.forEach { m ->
			assertTrue(m.archiveFormat in setOf("tar.bz2", "files"))
			assertTrue(m.files.isNotEmpty() && m.files.all { Regex("[0-9a-f]{64}").matches(it.sha256) && it.sizeBytes > 0 })
			assertTrue(m.files.all { com.eva.transcribe.data.PerFileModelPaths.isSafe(it.path) })
		}
		assertFalse("no FireRed CTC in place of AED", sherpa.any { it.modelUri.contains("ctc") })
	}

	@Test fun qwen17bIsPinnedPerFileModel() {
		val m = catalogue().models.single { it.modelId == "sherpa-qwen3-asr-1.7b-int8" }
		val rev = "69eb686fd94a4a865bb5340a3d6ac0d7f1fec0d5"
		assertEquals("sherpa-qwen3-asr", m.engine)
		assertEquals("files", m.archiveFormat)
		assertEquals(rev, m.version)
		assertEquals("https://huggingface.co/thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8/resolve/$rev", m.modelUri)
		assertTrue(m.displayName.contains("1.7B"))
		assertEquals(
			setOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json"),
			m.files.map { it.path }.toSet(),
		)
		assertEquals(2_404_222_421L, m.files.sumOf { it.sizeBytes })
		assertEquals(m.sizeBytes, m.files.sumOf { it.sizeBytes })
		val dec = m.files.single { it.path == "decoder.int8.onnx" }
		assertTrue("total > Int.MAX_VALUE: sizes and progress must stay Long", m.files.sumOf { it.sizeBytes } > Int.MAX_VALUE)
		assertEquals("c43c853fa6e97d08365cb8a5502b360b595cd43c00dc60e4d8ca7cc18cad460b", dec.sha256)
		// 0.6B remains, distinct id and name
		assertTrue(catalogue().models.single { it.modelId == "sherpa-qwen3-asr-0.6b-int8" }.displayName.contains("0.6B"))
	}

	@Test fun appConfigResolvesManifestFiles() {
		val m = catalogue().models.single { it.modelId == "sherpa-qwen3-asr-1.7b-int8" }
		val dir = kotlin.io.path.createTempDirectory("q17").toFile()
		m.files.forEach { File(dir, it.path).apply { parentFile.mkdirs(); writeText("x") } }
		val cfg = com.eva.transcribe.offline.OfflineSpeechEngine.buildConfig("sherpa-qwen3-asr", dir, 2)
		assertEquals(File(dir, "decoder.int8.onnx").absolutePath, cfg.modelConfig.qwen3Asr.decoder)
		assertEquals(512, cfg.modelConfig.qwen3Asr.maxTotalLen)
		assertEquals(128, cfg.featConfig.featureDim)
		dir.deleteRecursively()
	}
}

class PerFileModelTests {
	@Test fun unsafePathsRejected() {
		val ok = com.eva.transcribe.data.PerFileModelPaths::isSafe
		assertTrue(ok("tokenizer/vocab.json")); assertTrue(ok("decoder.int8.onnx"))
		listOf("../x", "/abs", "a/../b", "a\\b", ".hidden", "a/./b", "", "x.part").forEach { assertFalse(it, ok(it)) }
	}

	@Test fun qwenPrefixStripped() {
		val c = com.eva.transcribe.offline.OfflineSpeechEngine::cleanQwenText
		assertEquals("哦，这么technical的。", c("language Chinese<asr_text>哦，这么technical的。"))
		assertEquals("所以，我上学期也学了Python。", c("所以，我上学期也学了Python。"))
	}

	@Test fun ramRequirementUsesLongs() {
		val need = com.eva.transcribe.offline.OfflineSpeechEngine.requiredRamBytes(2_399_761_248L)
		assertTrue(need > 3_500_000_000L && need < 4_000_000_000L)
	}
}
