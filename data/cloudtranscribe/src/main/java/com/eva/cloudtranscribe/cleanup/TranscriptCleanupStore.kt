package com.eva.cloudtranscribe.cleanup

import com.eva.cloudtranscribe.store.AtomicTextFile
import java.io.File
import kotlinx.serialization.json.Json

/**
 * `<root>/v1/<recordingId>/cleanups/<INTENSITY>.json`: newest cleaned version per intensity. Kept apart from the
 * transcript files, which this store never touches.
 */
class TranscriptCleanupStore(root: File) {
	private val base = File(root, "v1")
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val lock = Any()

	private fun file(recordingId: Long, intensity: CleanupIntensity) = File(File(File(base, recordingId.toString()), "cleanups"), "${intensity.name}.json")

	fun cleanup(recordingId: Long, intensity: CleanupIntensity): CleanupRecord? = synchronized(lock) {
		val f = file(recordingId, intensity)
		if (!f.exists()) null else runCatching { json.decodeFromString(CleanupRecord.serializer(), f.readText()) }.getOrNull()
	}

	fun save(record: CleanupRecord) = synchronized(lock) {
		AtomicTextFile.write(file(record.recordingId, record.intensity), json.encodeToString(CleanupRecord.serializer(), record))
	}

	fun delete(recordingId: Long, intensity: CleanupIntensity) = synchronized(lock) { file(recordingId, intensity).delete() }
}
