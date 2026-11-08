package com.eva.transcribe.archive

import com.eva.transcribe.data.models.ModelFileEntry
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Extracts ONLY whitelisted regular files from a tar.bz2 model archive.
 * - single top-level directory is stripped
 * - symlinks / hardlinks / devices are ignored (never followed or created)
 * - absolute paths and `..` are rejected
 * - every extracted file must match the manifest size and SHA-256
 * - extraction aborts when bytes exceed the manifest total
 */
object SafeTarBz2Extractor {

	fun normalize(name: String): String? {
		val n = name.replace('\\', '/').trimStart('.').trimStart('/')
		if (name.startsWith("/") || n.split('/').any { it == ".." }) throw SecurityException("Unsafe archive entry: $name")
		val parts = n.split('/').filter { it.isNotEmpty() }
		if (parts.size < 2) return null // top-level dir or file outside the root
		return parts.drop(1).joinToString("/")
	}

	fun extract(
		archive: InputStream, destination: File, files: List<ModelFileEntry>, isCancelled: () -> Boolean = { false },
		onBytes: (written: Long, total: Long) -> Unit = { _, _ -> },
	) {
		require(files.isNotEmpty()) { "Manifest is empty" }
		val wanted = files.associateBy { it.path }
		val limit = files.sumOf { it.sizeBytes }
		val done = HashSet<String>()
		var total = 0L
		destination.mkdirs()
		val root = destination.canonicalFile
		TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive, 1 shl 16), true)).use { tar ->
			while (true) {
				val entry = tar.nextEntry ?: break
				if (isCancelled()) throw InterruptedException("cancelled")
				val rel = normalize(entry.name) ?: continue
				if (!entry.isFile || entry.isSymbolicLink || entry.isLink) continue
				val spec = wanted[rel] ?: continue
				if (rel in done) throw IOException("Duplicate entry $rel")
				if (entry.size != spec.sizeBytes) throw IOException("Size mismatch for $rel")
				val out = File(root, rel).canonicalFile
				if (!out.path.startsWith(root.path + File.separator)) throw SecurityException("Escapes destination: $rel")
				out.parentFile?.mkdirs()
				val md = MessageDigest.getInstance("SHA-256")
				val buf = ByteArray(1 shl 16)
				var written = 0L
				out.outputStream().use { o ->
					while (true) {
						if (isCancelled()) throw InterruptedException("cancelled")
						val n = tar.read(buf)
						if (n < 0) break
						written += n; total += n
						if (written > spec.sizeBytes || total > limit) throw IOException("Archive exceeds manifest size")
						md.update(buf, 0, n)
						onBytes(total, limit)
						o.write(buf, 0, n)
					}
				}
				val hex = md.digest().joinToString("") { "%02x".format(it) }
				if (written != spec.sizeBytes || !hex.equals(spec.sha256, ignoreCase = true)) {
					out.delete(); throw IOException("Checksum mismatch for $rel")
				}
				done += rel
			}
		}
		val missing = wanted.keys - done
		if (missing.isNotEmpty()) throw IOException("Archive missing files: $missing")
	}
}
