package com.eva.cloudtranscribe.store

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The one local follow-up draft of a recording. Never uploaded or sent by the app. */
@Serializable
data class FollowUpDraft(
	val audioId: Long,
	val draftText: String,
	val updatedAtMillis: Long,
	/** [com.eva.cloudtranscribe.followup.FollowUpPlanner.revisionOf] of the material it came from. */
	val sourceRevision: String,
)

/** File-backed (`<root>/<audioId>.json`), one draft per recording; no Room migration needed. */
class FollowUpDraftStore(private val root: File) {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val lock = Any()

	private fun file(audioId: Long) = File(root, "$audioId.json")

	fun read(audioId: Long): FollowUpDraft? = synchronized(lock) {
		val f = file(audioId)
		if (!f.exists()) return null
		runCatching { json.decodeFromString(FollowUpDraft.serializer(), f.readText()) }.getOrNull()
	}

	fun save(draft: FollowUpDraft) = synchronized(lock) {
		AtomicTextFile.write(file(draft.audioId), json.encodeToString(FollowUpDraft.serializer(), draft))
	}

	fun delete(audioId: Long) = synchronized(lock) { file(audioId).delete() }
}
