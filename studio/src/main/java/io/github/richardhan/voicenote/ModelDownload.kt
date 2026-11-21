package io.github.richardhan.voicenote

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ModelDownload(
	private val url: URL,
	private val destination: File,
	val expectedBytes: Long,
	private val expectedSha256: String
) {
	private companion object { val writer = Mutex() }

	private val partial = File(destination.parentFile, destination.name + ".part")
	val savedBytes get() = partial.length().coerceAtMost(expectedBytes)

	private fun clearPartial() { check(!partial.exists() || partial.delete()) }

	suspend fun discard() = writer.withLock { clearPartial() }
	suspend fun uninstall() = writer.withLock { check(!destination.exists() || destination.delete()) }

	suspend fun install(progress: suspend (Long) -> Unit) = writer.withLock {
		if (destination.length() == expectedBytes) return@withLock
		if (partial.length() > expectedBytes) clearPartial()
		val offset = partial.length()
		if (offset < expectedBytes) transfer(offset, progress)
		check(partial.length() == expectedBytes)
		val hash = MessageDigest.getInstance("SHA-256")
		val buffer = ByteArray(65536)
		partial.inputStream().use { input ->
			while (true) {
				currentCoroutineContext().ensureActive()
				val n = input.read(buffer)
				if (n < 0) break
				hash.update(buffer, 0, n)
			}
		}
		if (hash.digest().joinToString("") { "%02x".format(it) } != expectedSha256) {
			clearPartial()
			error("Model checksum mismatch")
		}
		currentCoroutineContext().ensureActive()
		check(partial.renameTo(destination))
	}

	private suspend fun transfer(offset: Long, progress: suspend (Long) -> Unit) {
		val conn = url.openConnection() as HttpURLConnection
		conn.connectTimeout = 20000
		conn.readTimeout = 20000
		conn.setRequestProperty("Accept-Encoding", "identity")
		if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
		try {
			val append = when (conn.responseCode) {
				HttpURLConnection.HTTP_PARTIAL -> {
					check(conn.getHeaderField("Content-Range") == "bytes $offset-${expectedBytes - 1}/$expectedBytes")
					true
				}
				HttpURLConnection.HTTP_OK -> false
				else -> error("Model download HTTP ${conn.responseCode}")
			}
			var total = if (append) offset else 0L
			conn.inputStream.use { input ->
				FileOutputStream(partial, append).buffered().use { output ->
					progress(total)
					val buffer = ByteArray(65536)
					var lastReport = total
					while (true) {
						currentCoroutineContext().ensureActive()
						val n = input.read(buffer)
						if (n < 0) break
						check(total + n <= expectedBytes)
						output.write(buffer, 0, n)
						total += n
						if (total - lastReport >= 1024 * 1024 || total == expectedBytes) {
							progress(total)
							lastReport = total
						}
					}
				}
			}
			check(total == expectedBytes)
		} finally { conn.disconnect() }
	}
}
