package com.eva.cloudtranscribe.store

import com.eva.cloudtranscribe.engine.ChunkCheckpoints
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptEditing
import com.eva.cloudtranscribe.model.TranscriptEdits
import com.eva.cloudtranscribe.model.TranscriptRecord
import java.io.File
import kotlinx.serialization.json.Json

/**
 * File-backed per-recording store (versioned JSON, no Room migration needed):
 * `<root>/v1/<recordingId>/transcripts/<id>.json`  — offline OR cloud transcripts (never overwritten by the other source)
 * `<root>/v1/<recordingId>/summaries/<id>.json`     — summaries, each pointing at its source transcript id
 * `<root>/v1/<recordingId>/checkpoints/<runId>/NNN.json` — per-chunk cloud checkpoints, removed only after success or explicit cleanup
 * `<root>/v1/<recordingId>/edits/<transcriptId>.json` — on-device user corrections, an overlay; the transcript file itself is never rewritten
 * The raw recording is never touched.
 */
class TranscriptStore(root: File) {
	private val base = File(root, "v1")
	private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
	private val lock = Any()

	private fun recDir(recordingId: Long) = File(base, recordingId.toString())
	private fun transcriptsDir(id: Long) = File(recDir(id), "transcripts")
	private fun summariesDir(id: Long) = File(recDir(id), "summaries")
	private fun editsDir(id: Long) = File(recDir(id), "edits")
	fun checkpointDir(recordingId: Long, runId: String) = File(File(recDir(recordingId), "checkpoints"), safe(runId))

	fun saveTranscript(record: TranscriptRecord) = write(File(transcriptsDir(record.recordingId), "${safe(record.id)}.json"), json.encodeToString(TranscriptRecord.serializer(), record))
	fun saveSummary(record: SummaryRecord) = write(File(summariesDir(record.recordingId), "${safe(record.id)}.json"), json.encodeToString(SummaryRecord.serializer(), record))

	fun transcript(recordingId: Long, id: String): TranscriptRecord? = read(File(transcriptsDir(recordingId), "${safe(id)}.json"))?.let { json.decodeFromString(TranscriptRecord.serializer(), it) }
	fun summary(recordingId: Long, id: String): SummaryRecord? = read(File(summariesDir(recordingId), "${safe(id)}.json"))?.let { json.decodeFromString(SummaryRecord.serializer(), it) }

	fun transcripts(recordingId: Long): List<TranscriptRecord> = list(transcriptsDir(recordingId)) { json.decodeFromString(TranscriptRecord.serializer(), it) }.sortedByDescending { it.createdAtMillis }
	fun summaries(recordingId: Long): List<SummaryRecord> = list(summariesDir(recordingId)) { json.decodeFromString(SummaryRecord.serializer(), it) }.sortedByDescending { it.createdAtMillis }

	fun edits(recordingId: Long, transcriptId: String): TranscriptEdits? = read(File(editsDir(recordingId), "${safe(transcriptId)}.json"))
		?.let { runCatching { json.decodeFromString(TranscriptEdits.serializer(), it) }.getOrNull() }
		?.takeIf { it.transcriptId == transcriptId }

	fun saveEdits(edits: TranscriptEdits) = write(File(editsDir(edits.recordingId), "${safe(edits.transcriptId)}.json"), json.encodeToString(TranscriptEdits.serializer(), edits))

	fun deleteEdits(recordingId: Long, transcriptId: String) { synchronized(lock) { File(editsDir(recordingId), "${safe(transcriptId)}.json").delete() } }

	/** [transcripts] with the user's on-device corrections applied (originals with changed sources are returned as is). */
	fun transcriptsWithEdits(recordingId: Long): List<TranscriptRecord> =
		transcripts(recordingId).map { TranscriptEditing.apply(it, edits(recordingId, it.id)) }

	fun deleteTranscript(recordingId: Long, id: String) {
		File(transcriptsDir(recordingId), "${safe(id)}.json").delete()
		deleteEdits(recordingId, id)
		clearCheckpoints(recordingId, id)
	}

	fun deleteSummary(recordingId: Long, id: String) { File(summariesDir(recordingId), "${safe(id)}.json").delete() }
	fun clearCheckpoints(recordingId: Long, runId: String) { checkpointDir(recordingId, runId).deleteRecursively() }

	fun checkpoints(recordingId: Long, runId: String): ChunkCheckpoints = object : ChunkCheckpoints {
		val dir = checkpointDir(recordingId, runId)
		override fun load(index: Int): SegmentResult? = read(File(dir, "%03d.json".format(index)))?.let {
			runCatching { json.decodeFromString(SegmentResult.serializer(), it) }.getOrNull()
		}
		override fun save(result: SegmentResult) = write(File(dir, "%03d.json".format(result.index)), json.encodeToString(SegmentResult.serializer(), result))
	}

	private fun <T> list(dir: File, decode: (String) -> T): List<T> =
		dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f -> read(f)?.let { runCatching { decode(it) }.getOrNull() } }

	private fun read(file: File): String? = synchronized(lock) { if (file.exists()) file.readText() else null }

	/** Atomic write: temp file + rename, so a crash never leaves a half-written result. */
	private fun write(file: File, text: String) = synchronized(lock) {
		AtomicTextFile.write(file, text)
		Unit
	}

	/** Recording ids that currently hold transcript data (read-only; newest folder first). */
	fun recordingIds(): List<Long> = base.listFiles { f -> f.isDirectory && f.name.toLongOrNull() != null }
		.orEmpty().sortedByDescending { it.lastModified() }.mapNotNull { it.name.toLongOrNull() }

	/** Newest mtime among this recording's transcript and correction files; 0 when it has none (cheap cache-invalidation key). */
	fun transcriptsSignature(recordingId: Long): Long = listOf(transcriptsDir(recordingId), editsDir(recordingId))
		.flatMap { it.listFiles { f -> f.extension == "json" }.orEmpty().toList() }.maxOfOrNull { it.lastModified() } ?: 0L

	private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
