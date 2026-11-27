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
		(server.url(url.file).toUrl().openConnection() as HttpsURLConnection).apply { sslSocketFactory = client.sslSocketFactory() }
	}
	private lateinit var audio: File
	private fun reply(code: Int, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

	@Before fun start() {
		server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
		server.start(InetAddress.getByName("127.0.0.1"), 0)
		audio = File.createTempFile("service-", ".m4a", File(app.filesDir, "audio").apply { mkdirs() }).apply { writeText("synthetic audio bytes") }
		app.library.refresh()
	}
	@After fun close() {
		server.shutdown()
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
			assertTrue(server.takeRequest().path!!.contains("generateIds"))
			assertTrue(server.takeRequest().path!!.contains("test-id"))
			assertEquals("test-id", JSONObject(server.takeRequest().body.readUtf8()).getString("id"))
			val upload = server.takeRequest()
			assertEquals("PUT", upload.method)
			assertEquals(audio.readText(), upload.body.readUtf8())
			reply(200, """{"id":"test-id","size":"${audio.length()}","trashed":false}""")
			assertEquals(Result.success(), worker.upload(open) { "local-test-token" })
			assertEquals("GET", server.takeRequest().method)
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
			assertEquals(2800, JSONObject(server.takeRequest().body.readUtf8()).getString("text").length)
			assertEquals("tail", JSONObject(server.takeRequest().body.readUtf8()).getString("text"))
			reply(200, "ok")
			assertEquals(Result.success(), worker.deliver(open))
			assertEquals("tail", JSONObject(server.takeRequest().body.readUtf8()).getString("text"))
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
		assertTrue(server.takeRequest().body.readUtf8().contains("synthetic audio bytes"))
		reply(200, """{"choices":[{"message":{"content":"Synthetic [1]"}}]}""")
		complete { speech.ask(audio, readTranscript(audio), "What happened?") }
		val chat = readMessages(chatFile(audio, readTranscript(audio)))
		assertEquals("Synthetic [1]", chat.getJSONObject(0).getString("answer"))
		assertEquals(listOf(1), sourceNumbers(chat.getJSONObject(0).getString("answer"), 1))
		assertTrue(server.takeRequest().body.readUtf8().contains("[1] Synthetic meeting."))
		reply(401)
		complete { speech.ask(audio, readTranscript(audio), "Try again") }
		assertEquals(401, speech.httpStatus)
		assertEquals(1, readMessages(chatFile(audio, readTranscript(audio))).length())
	}
}
