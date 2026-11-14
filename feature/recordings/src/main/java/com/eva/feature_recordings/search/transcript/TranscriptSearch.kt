package com.eva.feature_recordings.search.transcript

import com.eva.cloudtranscribe.model.PlaybackLine
import com.eva.cloudtranscribe.model.TranscriptSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** What the transcript search needs to know about a recording; everything else stays out of this file. */
internal data class TranscriptSearchRecording(val id: Long, val name: String)

/** One matched line of a recording's default transcript. [startMs] stays null unless that transcript has real timestamps. */
internal data class TranscriptHit(
	val recordingId: Long,
	val recordingName: String,
	val text: String,
	val startMs: Long?,
)

internal data class TranscriptSearchResult(
	val hits: ImmutableList<TranscriptHit> = persistentListOf(),
	val truncated: Boolean = false,
)

/**
 * On-device search over the default transcript of every recording. Reuses [TranscriptSearch.matchingLines] so a hit here
 * and a hit in the player can never disagree. Loads one recording at a time, so a cancelled search stops at the next
 * suspension point instead of walking every file.
 */
internal class TranscriptSearcher(private val maxHits: Int = DEFAULT_MAX_HITS) {

	suspend fun search(
		query: String,
		recordings: List<TranscriptSearchRecording>,
		lines: suspend (Long) -> List<PlaybackLine>?,
	): TranscriptSearchResult {
		val q = query.trim()
		if (q.isEmpty() || recordings.isEmpty()) return TranscriptSearchResult()
		val hits = mutableListOf<TranscriptHit>()
		for (recording in recordings) {
			val recordingLines = lines(recording.id) ?: continue
			for (index in TranscriptSearch.matchingLines(recordingLines, q)) {
				if (hits.size >= maxHits) return TranscriptSearchResult(hits.toImmutableList(), truncated = true)
				val line = recordingLines[index]
				hits += TranscriptHit(recording.id, recording.name, line.text, line.startMs)
			}
		}
		return TranscriptSearchResult(hits.toImmutableList(), truncated = false)
	}

	companion object {
		/** Enough to answer "which meeting was it" without turning the list into an endless scan. */
		const val DEFAULT_MAX_HITS = 100
	}
}

/**
 * Debounced search flow: waits a moment after the last keystroke, then keeps only the newest search running, and does
 * the file walking on [dispatcher] so the main thread never touches the transcript store.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal fun Flow<String>.transcriptSearch(
	searcher: TranscriptSearcher,
	recordings: () -> List<TranscriptSearchRecording>,
	lines: suspend (Long) -> List<PlaybackLine>?,
	dispatcher: CoroutineDispatcher,
): Flow<TranscriptSearchResult> = debounce(SEARCH_DEBOUNCE_MS.milliseconds)
	.mapLatest { query -> withContext(dispatcher) { searcher.search(query, recordings(), lines) } }

internal const val SEARCH_DEBOUNCE_MS = 300L
