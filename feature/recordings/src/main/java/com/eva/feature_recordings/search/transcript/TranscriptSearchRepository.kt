package com.eva.feature_recordings.search.transcript

import com.eva.cloudtranscribe.model.PlaybackLine
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.store.TranscriptStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches the default transcript lines of a recording, keyed by [TranscriptStore.transcriptsSignature] (newest file
 * mtime). A keystroke therefore re-reads at most the files that actually changed, and a recording with no usable
 * transcript is remembered as such instead of being scanned again.
 */
internal class TranscriptLineCache(
	private val signature: (Long) -> Long,
	private val load: (Long) -> List<PlaybackLine>?,
) {
	private data class Entry(val signature: Long, val lines: List<PlaybackLine>?)

	private val entries = HashMap<Long, Entry>()

	@Synchronized
	fun lines(recordingId: Long): List<PlaybackLine>? {
		val current = signature(recordingId)
		entries[recordingId]?.let { cached -> if (cached.signature == current) return cached.lines }
		val loaded = load(recordingId)
		entries[recordingId] = Entry(current, loaded)
		return loaded
	}

	@Synchronized
	fun clear() = entries.clear()
}

/** Read-only access to saved transcripts for the search screen; never writes, never leaves the device. */
@Singleton
class TranscriptSearchRepository @Inject constructor(store: TranscriptStore) {

	private val cache = TranscriptLineCache(
		signature = { recordingId -> store.transcriptsSignature(recordingId) },
		load = { recordingId ->
			val selected = PlaybackTranscripts.defaultSelection(store.transcriptsWithEdits(recordingId))
			selected?.let { PlaybackTranscripts.lines(it) }
		},
	)

	/** Lines of the recording's default transcript; null when it has none worth searching. */
	fun lines(recordingId: Long): List<PlaybackLine>? = cache.lines(recordingId)

	/** Drops cached lines, e.g. after a transcript was deleted or re-saved from another screen. */
	fun invalidate() = cache.clear()
}
