package com.eva.cloudtranscribe.notes

import android.content.Context
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.store.AtomicTextFile
import com.eva.cloudtranscribe.store.TranscriptStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Pre-/in-meeting notes of one recording (agenda, names, context). Phone-only until a cloud summary the user enabled. */
@Serializable
data class RecordingNote(val text: String, val updatedAtMillis: Long)

object RecordingNotes {
	const val MAX_CHARS = LiveCloudSettings.MAX_MEETING_NOTES

	fun clean(text: String): String = text.trim().take(MAX_CHARS)

	/** Per-recording notes replace the global default notes; the global ones are only a fallback. */
	fun effective(perRecording: String?, globalDefault: String): String =
		clean(perRecording.orEmpty()).ifEmpty { clean(globalDefault) }

	/** Reference-only block for the post-recording summary; empty notes leave the prompt exactly unchanged. */
	fun withNotes(prompt: String, notes: String?): String {
		val cleaned = clean(notes.orEmpty())
		if (cleaned.isEmpty()) return prompt
		val marker = "\n\n逐字稿：\n"
		val block = "\n\n【使用者備註（只供參考：用來聚焦與辨認名詞，不代表會中說過；不要據此新增重點）】\n$cleaned"
		val at = prompt.indexOf(marker)
		return if (at < 0) prompt + block else prompt.substring(0, at) + block + prompt.substring(at)
	}
}

/**
 * Draft notes for the recording that is about to start / currently running. Bound to the recording when it is saved,
 * discarded when it is cancelled, so notes never leak into the next recording. A draft left over from a previous app
 * process is never applied automatically: it is parked as [leftover] until the user applies or discards it.
 */
@Singleton
class RecordingNotesDraft internal constructor(
	private val dir: File,
	private val store: TranscriptStore,
	private val clock: () -> Long,
) {
	@Inject
	constructor(@ApplicationContext context: Context, store: TranscriptStore) :
		this(File(context.filesDir, "recording_notes"), store, { System.currentTimeMillis() })

	private val json = Json { ignoreUnknownKeys = true }
	private val draftFile = File(dir, "draft.json")
	private val leftoverFile = File(dir, "leftover.json")
	private val lock = Any()

	private val _text = MutableStateFlow("")
	val text: StateFlow<String> = _text.asStateFlow()

	private val _leftover = MutableStateFlow<RecordingNote?>(null)
	val leftover: StateFlow<RecordingNote?> = _leftover.asStateFlow()

	init {
		synchronized(lock) {
			val previous = read(draftFile)
			if (previous != null && previous.text.isNotBlank()) {
				val older = read(leftoverFile)
				val keep = if (older != null && older.updatedAtMillis > previous.updatedAtMillis) older else previous
				write(leftoverFile, keep)
			}
			draftFile.delete()
			_leftover.value = read(leftoverFile)?.takeIf { it.text.isNotBlank() }
		}
	}

	fun current(): String = _text.value

	fun update(text: String) = synchronized(lock) {
		val cleaned = text.take(RecordingNotes.MAX_CHARS)
		_text.value = cleaned
		if (cleaned.isBlank()) draftFile.delete() else write(draftFile, RecordingNote(cleaned, clock()))
	}

	/** Recording saved: the draft becomes this recording's notes and the draft is cleared for the next one. */
	fun bindTo(recordingId: Long) = synchronized(lock) {
		val cleaned = RecordingNotes.clean(_text.value)
		if (cleaned.isNotEmpty()) store.saveNotes(recordingId, RecordingNote(cleaned, clock()))
		clearDraft()
	}

	/** Recording cancelled: its draft is dropped. */
	fun discard() = synchronized(lock) { clearDraft() }

	fun applyLeftover() = synchronized(lock) {
		val note = _leftover.value ?: return@synchronized
		val merged = listOf(_text.value.trim(), note.text.trim()).filter { it.isNotEmpty() }.distinct().joinToString("\n")
		leftoverFile.delete()
		_leftover.value = null
		update(merged)
	}

	fun discardLeftover() = synchronized(lock) {
		leftoverFile.delete()
		_leftover.value = null
	}

	private fun clearDraft() {
		_text.value = ""
		draftFile.delete()
	}

	private fun read(file: File): RecordingNote? =
		if (!file.exists()) null else runCatching { json.decodeFromString(RecordingNote.serializer(), file.readText()) }.getOrNull()

	private fun write(file: File, note: RecordingNote) {
		AtomicTextFile.write(file, json.encodeToString(RecordingNote.serializer(), note))
	}
}
