package io.github.richardhan.voicenote

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloadTest {
	@get:Rule val dir = TemporaryFolder()
	private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
	private val bytes = "verified model contents".toByteArray()
	private val url = URL("http://127.0.0.1:${server.address.port}/weights")
	private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
	private val installed get() = File(dir.root, "model")
	private val partial get() = File(dir.root, "model.part")
	private fun transfer() = ModelDownload(url, installed, bytes.size.toLong(), hash)
	private var range: String? = null

	private fun serve(code: Int, body: ByteArray, contentRange: String? = null) {
		server.createContext("/weights") { exchange ->
			range = exchange.requestHeaders.getFirst("Range")
			if (contentRange != null) exchange.responseHeaders.add("Content-Range", contentRange)
			exchange.sendResponseHeaders(code, body.size.toLong())
			exchange.responseBody.use { it.write(body) }
		}
		server.start()
	}

	@After fun stopServer() { server.stop(0) }

	@Test fun resumesSavedPrefixWithRangeAndVerifiesWholeFile() = runBlocking {
		partial.writeBytes(bytes.copyOf(4))
		serve(206, bytes.copyOfRange(4, bytes.size), "bytes 4-${bytes.lastIndex}/${bytes.size}")
		val updates = mutableListOf<Long>()
		transfer().install { updates.add(it) }
		assertEquals("bytes=4-", range)
		assertEquals(listOf(4L, bytes.size.toLong()), updates)
		assertArrayEquals(bytes, installed.readBytes())
		assertFalse(partial.exists())
	}

	@Test fun ignoredRangeReplacesPrefixRatherThanDuplicatingIt() = runBlocking {
		partial.writeText("junk")
		serve(200, bytes)
		transfer().install {}
		assertEquals("bytes=4-", range)
		assertArrayEquals(bytes, installed.readBytes())
	}

	@Test fun incorrectRangeNeverAppendsToSavedBytes() = runBlocking {
		partial.writeText("veri")
		serve(206, bytes, "bytes 0-${bytes.lastIndex}/${bytes.size}")
		assertTrue(runCatching { transfer().install {} }.isFailure)
		assertEquals("veri", partial.readText())
		assertFalse(installed.exists())
	}

	@Test fun incompleteTransferKeepsBytesForRetryOrDiscard() = runBlocking {
		serve(200, bytes.copyOf(7))
		assertTrue(runCatching { transfer().install {} }.isFailure)
		assertEquals(7L, transfer().savedBytes)
		transfer().discard()
		assertFalse(partial.exists())
		assertFalse(installed.exists())
	}

	@Test fun rejectsCorruptCompleteFileBeforeInstallation() = runBlocking {
		partial.writeBytes(ByteArray(bytes.size))
		assertTrue(runCatching { transfer().install {} }.isFailure)
		assertFalse(partial.exists())
		assertFalse(installed.exists())
	}
}
