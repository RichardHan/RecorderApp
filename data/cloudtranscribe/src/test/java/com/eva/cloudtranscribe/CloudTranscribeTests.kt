package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.api.AudioPayload
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClient
import com.eva.cloudtranscribe.api.GeminiNativeClient
import com.eva.cloudtranscribe.api.HttpResponse
import com.eva.cloudtranscribe.api.HttpTransport
import com.eva.cloudtranscribe.api.OpenAiCompatibleClient
import com.eva.cloudtranscribe.api.Redactor
import com.eva.cloudtranscribe.audio.Mp3Encoder
import com.eva.cloudtranscribe.audio.PcmFileSource
import com.eva.cloudtranscribe.engine.AudioRangeSource
import com.eva.cloudtranscribe.engine.ChunkCheckpoints
import com.eva.cloudtranscribe.engine.CloudTranscriptionEngine
import com.eva.cloudtranscribe.engine.EngineConfig
import com.eva.cloudtranscribe.model.ReasoningEffort
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SegmentStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.store.TranscriptStore
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KEY = "sk-test-SECRET-123456"

private class FakeSource(override val durationMs: Long, val silent: Set<Long> = emptySet()) : AudioRangeSource {
	override fun isSilent(startMs: Long, endMs: Long) = startMs in silent
	override fun encode(startMs: Long, endMs: Long) = AudioPayload("$startMs-$endMs".toByteArray())
}

private class MemCheckpoints : ChunkCheckpoints {
	val map = ConcurrentHashMap<Int, SegmentResult>()
	override fun load(index: Int) = map[index]
	override fun save(result: SegmentResult) { map[result.index] = result }
}

private class ScriptedClient(val script: (range: String, call: Int) -> CallOutcome) : CloudAiClient {
	val calls = ConcurrentHashMap<String, AtomicInteger>()
	val total = AtomicInteger()
	override fun transcribe(prompt: String, audio: AudioPayload): CallOutcome {
		val r = String(audio.bytes)
		total.incrementAndGet()
		val n = calls.getOrPut(r) { AtomicInteger() }.incrementAndGet()
		return script(r, n)
	}
	override fun complete(prompt: String) = CallOutcome.Text("summary")
}

private fun cfg(workers: Int = 3) = EngineConfig(chunkSeconds = 300, workers = workers, retryBaseDelayMs = 1)

class EngineTests {
	@Test fun chunksStayInOrderEvenWhenCompletedOutOfOrder() = runBlocking {
		val client = ScriptedClient { r, _ ->
			if (r.startsWith("0-")) Thread.sleep(50)
			CallOutcome.Text("T[$r]")
		}
		val res = CloudTranscriptionEngine(client, MemCheckpoints(), cfg()).run(FakeSource(1_000_000))
		assertEquals(RunStatus.SUCCESS, res.status)
		assertEquals(listOf(0, 1, 2, 3), res.segments.map { it.index })
		assertTrue(res.text.indexOf("T[0-") < res.text.indexOf("T[300000-") && res.text.indexOf("T[600000-") < res.text.indexOf("T[900000-"))
	}

	@Test fun retriesSameProviderAtMostThreeTimesThenFailsVisibly() = runBlocking {
		val client = ScriptedClient { _, _ -> CallOutcome.Failure("HTTP 503", retryable = true, httpCode = 503) }
		val res = CloudTranscriptionEngine(client, MemCheckpoints(), cfg()).run(FakeSource(200_000))
		assertEquals(3, client.total.get())
		assertEquals(SegmentStatus.FAILED, res.segments.single().status)
		assertTrue(res.text.contains("轉錄失敗"))
		assertEquals(RunStatus.FAILED, res.status)
	}

	@Test fun nonRetryableErrorStopsImmediately() = runBlocking {
		val client = ScriptedClient { _, _ -> CallOutcome.Failure("HTTP 401", retryable = false, httpCode = 401) }
		CloudTranscriptionEngine(client, MemCheckpoints(), cfg()).run(FakeSource(200_000))
		assertEquals(1, client.total.get())
	}

	@Test fun networkErrorIsNeverSilence_andEmptyIsMarkedNotSilent() = runBlocking {
		val client = ScriptedClient { r, _ -> if (r.startsWith("0-")) CallOutcome.Empty else CallOutcome.Text("ok") }
		val res = CloudTranscriptionEngine(client, MemCheckpoints(), cfg()).run(FakeSource(400_000))
		assertEquals(SegmentStatus.EMPTY, res.segments[0].status)
		assertEquals(2, client.calls["0-300000"]!!.get()) // one alt-prompt retry
		assertTrue(res.text.contains("空白回應"))
		assertEquals(RunStatus.PARTIAL, res.status)
	}

	@Test fun silentChunksAreUploadedByDefault() = runBlocking {
		val client = ScriptedClient { _, _ -> CallOutcome.Text("ok") }
		val res = CloudTranscriptionEngine(client, MemCheckpoints(), cfg()).run(FakeSource(400_000, silent = setOf(0)))
		assertEquals(2, client.total.get())
		assertEquals(SegmentStatus.OK, res.segments[0].status)
	}

	@Test fun realSilenceSkipsUploadOnlyWhenOptedIn() = runBlocking {
		val client = ScriptedClient { _, _ -> CallOutcome.Text("ok") }
		val res = CloudTranscriptionEngine(client, MemCheckpoints(), cfg().copy(skipSilentChunks = true)).run(FakeSource(400_000, silent = setOf(0)))
		assertEquals(SegmentStatus.SILENT, res.segments[0].status)
		assertEquals(1, client.total.get())
		assertEquals(RunStatus.SUCCESS, res.status)
	}

	@Test fun contentFilterSplitsBoundedAndMarksMissingParts() = runBlocking {
		val client = ScriptedClient { r, _ ->
			when (r) {
				"0-300000" -> CallOutcome.ContentFiltered
				"100000-200000" -> CallOutcome.ContentFiltered
				else -> CallOutcome.Text("part $r")
			}
		}
		val res = CloudTranscriptionEngine(client, MemCheckpoints(), cfg()).run(FakeSource(300_000))
		val seg = res.segments.single()
		assertEquals(3, seg.parts.size)
		assertTrue(seg.parts.all { it.endMs - it.startMs >= 60_000 })
		assertEquals(1, client.calls["100000-200000"]!!.get()) // sent exactly once
		assertTrue(res.text.contains("part 0-100000"))
		assertTrue(res.text.contains("內容過濾，未轉錄 0:01:40–0:03:20"))
		assertTrue(res.text.indexOf("part 0-100000") < res.text.indexOf("part 200000-300000"))
		assertEquals(4, client.total.get())
	}

	@Test fun shortFilteredChunkIsNotSplitBelowMinimum() = runBlocking {
		val client = ScriptedClient { _, _ -> CallOutcome.ContentFiltered }
		val res = CloudTranscriptionEngine(client, MemCheckpoints(), cfg()).run(FakeSource(90_000))
		assertEquals(1, client.total.get())
		assertTrue(res.text.contains("內容過濾"))
	}

	@Test fun checkpointsResumeOnlyFailedChunks() = runBlocking {
		val cp = MemCheckpoints()
		var fail = true
		val client = ScriptedClient { r, _ -> if (fail && r.startsWith("300000")) CallOutcome.Failure("x", false) else CallOutcome.Text("ok $r") }
		CloudTranscriptionEngine(client, cp, cfg()).run(FakeSource(600_000))
		assertEquals(2, client.total.get())
		fail = false
		val res = CloudTranscriptionEngine(client, cp, cfg()).run(FakeSource(600_000))
		assertEquals(3, client.total.get()) // only the failed chunk was re-sent
		assertEquals(RunStatus.SUCCESS, res.status)
	}

	@Test fun cancellationStopsWork() {
		val client = ScriptedClient { _, _ -> Thread.sleep(200); CallOutcome.Text("ok") }
		var cancelled = false
		try {
			runBlocking {
				withTimeout(150) { CloudTranscriptionEngine(client, MemCheckpoints(), cfg(workers = 1)).run(FakeSource(3_000_000)) }
			}
		} catch (e: CancellationException) { cancelled = true }
		assertTrue(cancelled)
		assertTrue(client.total.get() < 10)
	}

	@Test fun hallucinationWarningOptional() = runBlocking {
		val client = ScriptedClient { r, _ -> CallOutcome.Text(if (r.startsWith("0-")) "x".repeat(1000) else "short text") }
		val on = CloudTranscriptionEngine(client, MemCheckpoints(), cfg()).run(FakeSource(1_200_000))
		assertTrue(on.text.contains("可能幻覺"))
		val off = CloudTranscriptionEngine(client, MemCheckpoints(), cfg().copy(hallucinationWarning = false)).run(FakeSource(1_200_000))
		assertFalse(off.text.contains("可能幻覺"))
	}
}

class AdapterTests {
	private class Capture(val response: HttpResponse) : HttpTransport {
		var url = ""; var headers = emptyMap<String, String>(); var body = ""
		override fun postJson(url: String, headers: Map<String, String>, body: String, timeoutSeconds: Int): HttpResponse {
			this.url = url; this.headers = headers; this.body = body; return response
		}
	}

	@Test fun openAiRequestMatchesRonePipeline() {
		val t = Capture(HttpResponse(200, """{"choices":[{"message":{"content":"Alice: 你好 hello"},"finish_reason":"stop"}]}"""))
		val c = OpenAiCompatibleClient("https://rone.example.com/v1/", KEY, "gemini-3.8-flash", ReasoningEffort.LOW, t)
		val out = c.transcribe("prompt", AudioPayload(byteArrayOf(1, 2, 3)))
		assertEquals(CallOutcome.Text("Alice: 你好 hello"), out)
		assertEquals("https://rone.example.com/v1/chat/completions", t.url)
		assertEquals("Bearer $KEY", t.headers["Authorization"])
		val body = Json.parseToJsonElement(t.body).jsonObject
		assertEquals("gemini-3.8-flash", body["model"]!!.jsonPrimitive.content)
		assertEquals("16384", body["max_tokens"]!!.jsonPrimitive.content)
		assertEquals("false", body["stream"]!!.jsonPrimitive.content)
		assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
		val parts = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
		assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
		val audio = parts[1].jsonObject
		assertEquals("input_audio", audio["type"]!!.jsonPrimitive.content)
		assertEquals("AQID", audio["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content)
		assertEquals("mp3", audio["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
		assertFalse(t.body.contains(KEY))
	}

	@Test fun noReasoningEffortForTranscribeModels() {
		val t = Capture(HttpResponse(200, """{"choices":[]}"""))
		val c = OpenAiCompatibleClient("https://x.example", KEY, "gemini-3.5-transcribe", ReasoningEffort.LOW, t)
		assertEquals(CallOutcome.Empty, c.transcribe("p", AudioPayload(byteArrayOf(0))))
		assertFalse(t.body.contains("reasoning_effort"))
	}

	@Test fun contentFilterAndErrorsAreClassifiedWithoutLeakingKey() {
		assertEquals(CallOutcome.ContentFiltered, OpenAiCompatibleClient.parse("""{"choices":[{"message":{"content":""},"finish_reason":"content_filter"}]}"""))
		val t = Capture(HttpResponse(500, "upstream said Bearer $KEY invalid"))
		val out = OpenAiCompatibleClient("https://x.example", KEY, "m", ReasoningEffort.OFF, t).transcribe("p", AudioPayload(byteArrayOf(0))) as CallOutcome.Failure
		assertTrue(out.retryable)
		assertFalse(out.message.contains(KEY))
		val io = OpenAiCompatibleClient("https://x.example", KEY, "m", ReasoningEffort.OFF) { _, _, _, _ -> throw IOException("timeout $KEY") }
		val f = io.transcribe("p", AudioPayload(byteArrayOf(0))) as CallOutcome.Failure
		assertTrue(f.retryable); assertFalse(f.message.contains(KEY))
		assertEquals(false, (OpenAiCompatibleClient("https://x.example", KEY, "m", ReasoningEffort.OFF, Capture(HttpResponse(400, "bad"))).transcribe("p", AudioPayload(byteArrayOf(0))) as CallOutcome.Failure).retryable)
	}

	@Test fun geminiNativeSchema() {
		val t = Capture(HttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}]}"""))
		val c = GeminiNativeClient("", KEY, "gemini-3.5-transcribe", t)
		assertEquals(CallOutcome.Text("hi"), c.transcribe("p", AudioPayload(byteArrayOf(1, 2, 3))))
		assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent", t.url)
		assertEquals(KEY, t.headers["x-goog-api-key"])
		assertFalse(t.url.contains(KEY))
		val part = Json.parseToJsonElement(t.body).jsonObject["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[1].jsonObject["inline_data"]!!.jsonObject
		assertEquals("audio/mp3", part["mime_type"]!!.jsonPrimitive.content)
		assertEquals(CallOutcome.ContentFiltered, GeminiNativeClient.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
	}

	@Test fun redactorRemovesSecrets() {
		assertFalse(Redactor.redact("x $KEY y key=abc Bearer zzz", KEY).let { it.contains(KEY) || it.contains("zzz") || it.contains("abc") })
	}
}

class AudioAndStoreTests {
	@Test fun mp3EncoderProducesFrames() {
		val pcm = ShortArray(16_000 * 3) { i -> (Math.sin(i * 2 * Math.PI * 440 / 16000) * 8000).toInt().toShort() }
		val mp3 = Mp3Encoder.encode(pcm)
		assertTrue(mp3.size in 12_000..40_000) // ~64 kbps * 3s = 24 KB
		val idx = (0 until mp3.size - 1).first { (mp3[it].toInt() and 0xFF) == 0xFF && (mp3[it + 1].toInt() and 0xE0) == 0xE0 }
		assertTrue(idx < 1000)
	}

	@Test fun silenceDetectionIsRmsBased() {
		assertTrue(PcmFileSource.isSilent(ShortArray(16000) { (it % 3 - 1).toShort() }, 60.0))
		val speech = ShortArray(16000); for (i in 8000 until 9600) speech[i] = (if (i % 2 == 0) 3000 else -3000).toShort()
		assertFalse(PcmFileSource.isSilent(speech, 60.0))
	}

	@Test fun storeKeepsOfflineAndCloudSeparate() {
		val dir = Files.createTempDirectory("store").toFile()
		val store = TranscriptStore(dir)
		store.saveTranscript(TranscriptRecord("off1", 7, TranscriptSource.OFFLINE, Provenance("sherpa", model = "sensevoice"), 1, status = RunStatus.SUCCESS, text = "a"))
		store.saveTranscript(TranscriptRecord("cloud1", 7, TranscriptSource.CLOUD, Provenance("cloud", model = "gemini-3.8-flash"), 2, status = RunStatus.SUCCESS, text = "b"))
		assertEquals(setOf("off1", "cloud1"), store.transcripts(7).map { it.id }.toSet())
		assertEquals("a", store.transcript(7, "off1")!!.text)
		dir.walkTopDown().filter { it.isFile }.forEach { assertFalse(it.readText().contains(KEY)) }
	}
}
