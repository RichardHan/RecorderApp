package com.eva.cloudtranscribe.store

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Never delete the last saved meeting record to make room for a failed replacement. */
internal object AtomicTextFile {
	fun write(file: File, text: String, replace: (File, File) -> Unit = ::replaceAtomically) {
		val parent = file.absoluteFile.parentFile ?: throw IOException("Missing storage directory")
		if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create storage directory")
		// A unique sibling also avoids temp-file collisions between independent store instances.
		val temp = Files.createTempFile(parent.toPath(), ".record-", ".tmp").toFile()
		try {
			temp.writeText(text, Charsets.UTF_8)
			replace(temp, file)
		} finally {
			temp.delete()
		}
	}

	private fun replaceAtomically(temp: File, target: File) {
		// If atomic replacement is unsupported or fails, propagate the failure. Never unlink target.
		Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
	}
}
