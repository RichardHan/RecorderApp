package io.github.richardhan.voicenote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class LocalServiceTest {
	private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StudioApplication
	private val server = MockWebServer()
	private val certificate = HeldCertificate.Builder().commonName("Studio loopback").addSubjectAlternativeName("127.0.0.1").build()
	private val client = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
	private val open: HttpsOpen = { url ->
		(URL("https", "127.0.0.1", server.port, url.file).openConnection() as HttpsURLConnection).apply { sslSocketFactory = client.sslSocketFactory() }
	}
	private lateinit var audio: File
	private fun nextRequest() = requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) { "No loopback request received" }
	private fun reply(code: Int, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

	@Before fun start() {
		server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
		server.start(InetAddress.getByName("127.0.0.1"), 0)
		audio = File.createTempFile("service-", ".m4a", File(app.filesDir, "audio").apply { mkdirs() }).apply { writeText("synthetic audio bytes") }
		app.library.refresh()
	}
	@After fun close() {
		server.shutdown()
		audio.parentFile?.listFiles()?.filter { it.name.startsWith(audio.name + ".") && it.extension == "meeting" }?.forEach { it.delete() }
		audio.delete()
		transcriptFile(audio).delete()
		File(audio.parentFile, audio.name + ".usage").delete()
		chatFile(audio, "Synthetic meeting.").delete()
		app.library.refresh()
	}

	@Test fun driveRetryReusesIdAndChecksCompletedUpload() = runBlocking {
		val prefs = app.drivePrefs()
		val account = "studio-test@example.invalid"
		prefs.edit().putString("account", account).apply()
		try {
			val worker = TestListenableWorkerBuilder<DriveWorker>(app, inputData = workDataOf("account" to account, "file" to audio.name)).build()
			reply(200, """{"ids":["test-id"]}""")
			reply(404)
			server.enqueue(MockResponse().setResponseCode(200).setHeader("Location", "https://www.googleapis.com/upload/test-session"))
			reply(503)
			assertEquals(Result.retry(), worker.upload(open) { "local-test-token" })
			assertTrue(nextRequest().path!!.contains("generateIds"))
			assertTrue(nextRequest().path!!.contains("test-id"))
			assertEquals("test-id", JSONObject(nextRequest().body.readUtf8()).getString("id"))
			val upload = nextRequest()
			assertEquals("PUT", upload.method)
			assertEquals(audio.readText(), upload.body.readUtf8())
			reply(200, """{"id":"test-id","size":"${audio.length()}","trashed":false}""")
			assertEquals(Result.success(), worker.upload(open) { "local-test-token" })
			assertEquals("GET", nextRequest().method)
			assertEquals(5, server.requestCount)
			assertEquals("done", app.library.catalog.value(audio, "drive"))
		} finally { prefs.edit().remove("account").remove("id/$account/${audio.name}").apply() }
	}

	@Test fun slackResumesAfterRateLimitAndShowsUnconfirmedErrors() = runBlocking {
		val generation = UUID.randomUUID().toString()
		val prefs = app.slackPrefs()
		val vault = ConnectionVault(app, "studio-slack")
		prefs.edit().putString("generation", generation).apply()
		vault.save(JSONObject().put("hook", "https://hooks.slack.com/services/T/B/Synthetic").put("generation", generation))
		val id = UUID.randomUUID().toString()
		val payload = File(File(app.noBackupFilesDir, "slack-outbox").apply { mkdirs() }, id)
		try {
			val worker = TestListenableWorkerBuilder<SlackWorker>(app, inputData = workDataOf("file" to audio.name, "id" to id, "generation" to generation)).build()
			payload.writeText("a".repeat(2800) + "tail")
			reply(200, "ok"); reply(429)
			assertEquals(Result.retry(), worker.deliver(open))
			assertEquals(2800, JSONObject(nextRequest().body.readUtf8()).getString("text").length)
			assertEquals("tail", JSONObject(nextRequest().body.readUtf8()).getString("text"))
			reply(200, "ok")
			assertEquals(Result.success(), worker.deliver(open))
			assertEquals("tail", JSONObject(nextRequest().body.readUtf8()).getString("text"))
			assertFalse(payload.exists())
			assertEquals("sent", app.library.catalog.value(audio, "slack"))
			payload.writeText("new message")
			reply(500)
			worker.deliver(open)
			assertEquals("unconfirmed", app.library.catalog.value(audio, "slack"))
			assertEquals(4, server.requestCount)
		} finally { payload.delete(); vault.remove(); prefs.edit().remove("generation").apply() }
	}

	@Test fun transcriptionAndChatStoreOnlySuccessfulReplies() = runBlocking {
		val speech = SpeechConnection(app, open)
		speech.key = "local-test-token"
		speech.chatModel = "test-chat"
		suspend fun complete(action: () -> Unit) {
			withContext(Dispatchers.Main) { action() }
			withTimeout(12000) { while (withContext(Dispatchers.Main) { speech.busy }) delay(25) }
		}
		reply(200, """{"text":"Synthetic meeting."}""")
		complete { speech.transcribe(audio, false) }
		assertEquals("Synthetic meeting.", readTranscript(audio))
		assertTrue(nextRequest().body.readUtf8().contains("synthetic audio bytes"))
		reply(200, """{"choices":[{"message":{"content":"Synthetic [1]"}}]}""")
		complete { speech.ask(audio, readTranscript(audio), "What happened?") }
		val chat = readMessages(chatFile(audio, readTranscript(audio)))
		assertEquals("Synthetic [1]", chat.getJSONObject(0).getString("answer"))
		assertEquals(listOf(1), sourceNumbers(chat.getJSONObject(0).getString("answer"), 1))
		assertTrue(nextRequest().body.readUtf8().contains("[1] Synthetic meeting."))
		reply(401)
		complete { speech.ask(audio, readTranscript(audio), "Try again") }
		assertEquals(401, speech.httpStatus)
		assertEquals(1, readMessages(chatFile(audio, readTranscript(audio))).length())
	}
	@Test fun meetingToolsSendOnlyTextAndKeepValidatedResultsPerRecording() = runBlocking {
		val text = "Plan agreed.\nSend the draft."
		val segments = org.json.JSONArray("""[{"start":0,"end":2,"text":"Plan agreed."},{"start":5,"end":8,"text":"Send the draft."}]""")
		saveTranscription(audio, text, null, segments)
		val speech = SpeechConnection(app, open)
		speech.key = "synthetic"
		speech.chatModel = "notes-mock"
		val notes = MeetingNotes(audio, text)
		val passages = meetingPassages(audio, text)
		assertEquals(listOf(0L, 5000L), passages.map { it.ms })
		suspend fun complete(action: () -> Unit) {
			withContext(Dispatchers.Main) { action() }
			withTimeout(12000) { while (withContext(Dispatchers.Main) { speech.busy }) delay(25) }
		}
		val rows = """[{"text":"Result one","source":1,"quote":"agreed","group":"Topic"},{"text":"Result two","source":2,"quote":"draft","group":"Topic"}]"""
		fun response(body: String) = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", body)))).toString()
		for (tool in MeetingTool.entries) {
			val answer = if (tool == MeetingTool.Title) """[{"text":"Review","source":1,"quote":"agreed"}]""" else if (tool == MeetingTool.Outline) rows.replace("agreed", "Result one").replace("draft", "Result two") else rows
			reply(200, response(answer))
			complete { speech.generateMeetingNotes(audio, text, tool, "English", "English", false) }
			assertEquals(tool.name, 0, speech.error)
			val body = nextRequest().body.readUtf8()
			assertTrue(body.contains("notes-mock"))
			if (tool == MeetingTool.Outline) {
				assertTrue(body.contains("[2] Result two"))
				assertFalse(body.contains("Send the draft."))
			} else assertTrue(body.contains("[2] Send the draft."))
			assertFalse(body.contains("synthetic audio bytes"))
			val result = requireNotNull(notes.read(tool, "English"))
			assertEquals(0, result.points.first().source)
			assertNull(MeetingNotes(audio, "Different transcript").read(tool, "English"))
		}
		val translation = meetingDocument(MeetingTool.Translation, notes.read(MeetingTool.Translation, "English")!!, passages)
		assertTrue(translation.contains("Plan agreed.\nResult one"))
		assertTrue(translation.contains("Send the draft.\nResult two"))
		assertNull(notes.read(MeetingTool.Translation, "日本語"))
		reply(200, response("""[{"text":"Partial translation","source":5,"quote":"invented"}]"""))
		complete { speech.generateMeetingNotes(audio, text, MeetingTool.Translation, "English", "English", false) }
		assertTrue(speech.error != 0)
		assertEquals(2, notes.read(MeetingTool.Translation, "English")!!.points.size)
		nextRequest()
		val decision = notes.read(MeetingTool.Decisions)!!.points.first()
		complete { speech.reviseDecision(audio, text, decision, "Reviewed decision") }
		assertEquals("Reviewed decision", notes.read(MeetingTool.Decisions)!!.points.first().text)
		complete { speech.changeMeetingTasks(audio, text) { it + CheckedTask("Send draft", false) } }
		complete { speech.changeMeetingTasks(audio, text) { list -> list.map { it.copy(done = true) } } }
		assertEquals(listOf(CheckedTask("Send draft", true)), MeetingNotes(audio, "edited").tasks())
		reply(200, response("Please send the draft."))
		complete { speech.followUp(audio, text, "Send draft", "Brief") }
		val followUp = nextRequest().body.readUtf8()
		assertTrue(followUp.contains("Send draft"))
		assertFalse(followUp.contains("Plan agreed."))
		assertEquals("Please send the draft.", notes.draft()?.first)
		assertEquals(text, readTranscript(audio))
		val zip = java.io.ByteArrayOutputStream()
		exportTranscripts(zip, listOf(audio))
		val entries = java.util.zip.ZipInputStream(zip.toByteArray().inputStream()).use { input ->
			buildMap { while (true) { val entry = input.nextEntry ?: break; put(entry.name, input.readBytes().decodeToString()) } }
		}
		assertEquals("[x] Send draft", entries[audio.name + ".tasks.txt"])
		assertTrue(entries.getValue(audio.name + ".summary.txt").contains("Result one"))
		assertTrue(parseMeetingOutput(MeetingTool.Summary, """[{"text":"Unknown","source":2,"quote":"absent"}]""", passages, "").points.single().source == null)
		if (InstrumentationRegistry.getArguments().getString("previewFixture") == "true") {
			val fixture = File(audio.parentFile, "Review.wav")
			fixture.writeBytes(pcmWave(FloatArray(9 * 16000)))
			val source = "Plan agreed.\nSend the draft."
			saveTranscription(fixture, source, null, segments)
			val bilingual = """[{"text":"已同意計畫。","source":1,"quote":"Plan agreed."},{"text":"寄出草稿。","source":2,"quote":"Send the draft."}]"""
			reply(200, response(bilingual))
			complete { speech.generateMeetingNotes(fixture, source, MeetingTool.Translation, "繁體中文", "English", false) }
			assertEquals(0, speech.error)
		}
	}

	@Test fun chatMasksHistoryAndBoundsContextAndRespectsModelEffort() {
		val email = "person@example.org"
		val past = org.json.JSONArray().put(JSONObject().put("question", email).put("answer", email))
		val input = chatPayload("sample", email + "x".repeat(33000) + "SECRET_TAIL", past, email, true, email)
		assertFalse(input.toString().contains(email))
		assertFalse(input.toString().contains("SECRET_TAIL"))
		val speech = SpeechConnection(app, open)
		speech.effort = "low"
		speech.protocol = SpeechProtocol.Chat
		assertEquals("low", speech.effectiveEffort("gemini-4-hydrogen", true))
		speech.speakerLabels = true
		assertNull(speech.effectiveEffort("gemini-4-hydrogen", true))
		assertEquals("low", speech.effectiveEffort("gemini-4-hydrogen"))
		assertNull(speech.effectiveEffort("gemini-3.5-transcribe"))
		speech.protocol = SpeechProtocol.Gemini
		assertNull(speech.effectiveEffort("gemini-4-hydrogen"))
	}

	@Test fun monthlyUsageCountsOnlyReportedCostsAndTokens() {
		val usage = MonthlyUsage(app)
		val month = "test-" + UUID.randomUUID()
		try {
			usage.record(JSONObject("""{"usage":{"total_tokens":12,"cost":0.012}}"""), null, month)
			usage.record(JSONObject("""{"usageMetadata":{"totalTokenCount":8}}"""), null, month)
			usage.record(JSONObject(), null, month)
			val row = MonthlyUsage(app).row(month)
			assertEquals(3, row.getInt("requests"))
			assertEquals(20, row.getInt("tokens"))
			assertEquals(2, row.getInt("withTokens"))
			assertEquals(1, row.getInt("withCost"))
			assertEquals("0.012", row.getString("cost"))
		} finally { app.getSharedPreferences("studio_ai_usage", 0).edit().remove(month).apply() }
	}

	@Test fun geminiRetrySendsOnlyUnfinishedAudioAndUsesTheConfiguredKey() = runBlocking {
		val wave = File(audio.parentFile, audio.name + ".wav")
		try {
			wave.writeBytes(pcmWave(FloatArray(21 * 16000) { 0.1f }))
			val speech = SpeechConnection(app, open)
			speech.setCustomMode(true)
			speech.changeBase("https://example.invalid/v1beta")
			speech.key = "synthetic-gemini"
			speech.protocol = SpeechProtocol.Gemini
			speech.model = "test-gemini"
			val first = """{"candidates":[{"content":{"parts":[{"text":"First chunk"}]}}]}"""
			val second = first.replace("First chunk", "Final chunk")
			reply(200, first); reply(503)
			assertTrue(runCatching { speech.transcribeNow(wave, false) }.isFailure)
			assertFalse(transcriptFile(wave).exists())
			val req = nextRequest()
			assertEquals("/v1beta/models/test-gemini:generateContent", req.path)
			assertEquals("synthetic-gemini", req.getHeader("x-goog-api-key"))
			val inline = JSONObject(req.body.readUtf8()).getJSONArray("contents").getJSONObject(0)
				.getJSONArray("parts").getJSONObject(1).getJSONObject("inlineData")
			assertEquals("audio/wav", inline.getString("mimeType"))
			val bytes = android.util.Base64.decode(inline.getString("data"), android.util.Base64.DEFAULT)
			assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
			nextRequest()
			reply(200, second)
			speech.transcribeNow(wave, false)
			assertEquals("First chunk\nFinal chunk", readTranscript(wave))
			assertEquals(3, server.requestCount)
			assertEquals(listOf(0.0, 20.0), readTiming(wave).map { it.start })
		} finally {
			wave.delete()
			transcriptFile(wave).delete()
			timingFile(wave).delete()
			File(wave.parentFile, wave.name + ".usage").delete()
		}
	}

}
