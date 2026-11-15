package com.eva.feature_recordings.badges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.provider.RecordingActionItemsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local status of one recording shown on its list card (spec: market-list-badges). */
data class RecordingBadges(
	val hasTranscript: Boolean = false,
	val transcribing: Boolean = false,
	val hasSummary: Boolean = false,
	val openItems: Int = 0,
) {
	/** Ordered labels; empty means the card shows no extra line. */
	val labels: List<String>
		get() = buildList {
			if (hasTranscript) add("逐字稿") else if (transcribing) add("轉錄中")
			if (hasSummary) add("摘要")
			if (openItems > 0) add("待辦 $openItems")
		}

	companion object {
		const val MAX_RECORDINGS = 300

		fun of(transcripts: List<TranscriptRecord>, summaries: List<SummaryRecord>, openItems: Int) = RecordingBadges(
			hasTranscript = transcripts.any { (it.status == RunStatus.SUCCESS || it.status == RunStatus.PARTIAL) && (it.text.isNotBlank() || it.segments.any { s -> s.text.isNotBlank() }) },
			transcribing = transcripts.any { it.status == RunStatus.QUEUED || it.status == RunStatus.RUNNING },
			hasSummary = summaries.any { it.status == RunStatus.SUCCESS && it.text.isNotBlank() },
			openItems = openItems,
		)
	}
}

@HiltViewModel
internal class RecordingBadgesViewModel @Inject constructor(
	private val store: TranscriptStore,
	private val actionItems: RecordingActionItemsProvider,
) : ViewModel() {
	private val _badges = MutableStateFlow<Map<Long, RecordingBadges>>(emptyMap())
	val badges: StateFlow<Map<Long, RecordingBadges>> = _badges.asStateFlow()
	/** Transcript file signature per id, so unchanged transcripts are not parsed again. */
	private val transcriptCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, List<TranscriptRecord>>>()
	private var job: Job? = null

	fun refresh(ids: List<Long>) {
		job?.cancel()
		job = viewModelScope.launch {
			val result = withContext(Dispatchers.IO) {
				ids.take(RecordingBadges.MAX_RECORDINGS).mapNotNull { id ->
					kotlinx.coroutines.yield() // stop promptly when a newer refresh replaced this one
					runCatching {
						val sig = store.transcriptsSignature(id)
						val transcripts = transcriptCache[id]?.takeIf { it.first == sig }?.second
							?: store.transcripts(id).also { transcriptCache[id] = sig to it }
						val open = actionItems.actionItems(id).count { !it.isDone }
						id to RecordingBadges.of(transcripts, store.summaries(id), open)
					}.getOrNull()
				}.toMap()
			}
			_badges.value = result
		}
	}
}
