package com.eva.cloudtranscribe.slack

import android.content.Context
import com.eva.cloudtranscribe.store.AtomicTextFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Pending message bodies stay in private, non-backed-up files, never in WorkManager's 10 KiB Data. */
@Singleton
class SlackPayloadStore internal constructor(private val root: File) {
	@Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "slack_outbox"))

	fun save(text: String): String {
		val name = UUID.randomUUID().toString() + ".txt"
		val file = File(root, name)
		root.mkdirs()
		AtomicTextFile.write(file, text)
		return name
	}

	fun read(name: String): String? = file(name)?.takeIf { it.isFile }?.readText()
	fun delete(name: String) { file(name)?.delete() }

	private fun file(name: String): File? =
		if (Regex("[a-f0-9-]{36}\\.txt").matches(name)) File(root, name) else null
}
