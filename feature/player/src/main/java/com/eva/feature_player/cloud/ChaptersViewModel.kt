package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.chapters.ChapterPlanner
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.models.RecordingChapterModel
import com.eva.recordings.domain.provider.RecordingChaptersProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Chapters of one recording. Generation is manual and explicit: it sends the numbered transcript to the user's
 * configured service once, keeps nothing when cancelled or rejected, and never invents a time (indices are mapped to
 * real sentence times locally).
 */
@HiltViewModel
class ChaptersViewModel @Inject constructor(
	private val provider: RecordingChaptersProvider,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
	private val store: TranscriptStore,
) : ViewModel() {

	var chapters by mutableStateOf<List<RecordingChapterModel>>(emptyList())
		private set

	var generating by mutableStateOf(false)
		private set

	var message by mutableStateOf<String?>(null)
		private set

	/** True when the newest transcript revision differs from the one the chapters were made from. */
	var possiblyStale by mutableStateOf(false)
		private set

	/** The transcript the next generation would use (default selection), loaded on bind. */
	private var transcript: TranscriptRecord? = null

	/** False when the selected transcript has no real sentence times, so chapters cannot be generated. */
	var canGenerate by mutableStateOf(false)
		private set

	var blockedReason by mutableStateOf<String?>(null)
		private set

	private var recordingId = -1L
	private var sourceJob: Job? = null
	private var bound = false
	private var job: Job? = null

	fun bind(id: Long) {
		if (bound && recordingId == id) return
		recordingId = id
		bound = true
		sourceJob?.cancel()
		sourceJob = viewModelScope.launch {
			store.editChanges.collectLatest {
				withContext(Dispatchers.IO) {
					val selected = PlaybackTranscripts.defaultSelection(store.transcriptsWithEdits(id))
					val lines = selected?.let(PlaybackTranscripts::lines).orEmpty()
					transcript = selected
					canGenerate = selected != null && ChapterPlanner.hasTimedSentences(lines)
					blockedReason = when {
						selected == null -> UiStrings.get(StringsRes.string.feature_player_no_transcript_available_for_this_recording, "這段錄音還沒有可用的逐字稿")
						!ChapterPlanner.hasTimedSentences(lines) -> UiStrings.get(StringsRes.string.feature_player_chapter_generation_requires_sentence_timestamps, "這份逐字稿沒有真實分句時間，無法產生章節")
						else -> null
					}
					if (selected != null) {
						val current = ChapterPlanner.revisionOf(selected.id, selected.updatedAtMillis)
						possiblyStale = provider.chapters(id).let { list -> list.isNotEmpty() && list.any { it.transcriptRevision != current } }
					}
				}
				provider.chaptersAsFlow(id)
					.catch { message = UiStrings.get(StringsRes.string.feature_player_could_not_load_chapters, "無法讀取章節：%1\$s", it.message ?: UiStrings.get(StringsRes.string.feature_player_unknown_error, "未知錯誤")); emit(emptyList()) }
					.collect { chapters = it }
			}
		}
	}

	fun dismissMessage() { message = null }

	/** Last sentence range the generation would use; used by the confirmation dialog. */
	fun generate() {
		if (generating) return
		val transcript = transcript ?: return
		val lines = PlaybackTranscripts.lines(transcript)
		if (!ChapterPlanner.hasTimedSentences(lines)) {
			message = UiStrings.get(StringsRes.string.feature_player_chapter_generation_requires_sentence_timestamps, "這份逐字稿沒有真實分句時間，無法產生章節")
			return
		}
		val sentences = ChapterPlanner.sentences(lines)
		if (sentences.isEmpty()) {
			message = UiStrings.get(StringsRes.string.feature_player_no_usable_sentences_in_this_transcript, "這份逐字稿沒有可用的分句")
			return
		}
		job?.cancel()
		job = viewModelScope.launch {
			generating = true
			message = null
			try {
				val settings = settingsRepo.current()
				val key = keys.read()
				if (settings.baseUrl.isBlank() || key.isNullOrBlank()) {
					message = UiStrings.get(StringsRes.string.feature_player_set_a_cloud_summary_service_and_api, "尚未設定雲端摘要服務或 API Key")
					return@launch
				}
				val client = CloudAiClients.create(settings, key, UrlConnectionTransport(), settings.summaryModel)
				val prompt = ChapterPlanner.prompt(sentences).let { if (settings.redactPiiForCloud) com.eva.cloudtranscribe.model.PiiRedactor.redact(it) else it }
				val outcome = withContext(Dispatchers.IO) { client.complete(prompt) }
				when (outcome) {
					is CallOutcome.Text -> {
						val drafts = ChapterPlanner.parse(outcome.text, sentences)
						if (drafts.isNullOrEmpty()) {
							message = UiStrings.get(StringsRes.string.feature_player_invalid_chapter_indices_or_ordering_nothing_was, "回應不是有效章節（索引越界或未遞增），未儲存任何內容")
							return@launch
						}
						val revision = ChapterPlanner.revisionOf(transcript.id, transcript.updatedAtMillis)
						val now = System.currentTimeMillis()
						provider.replaceAll(
							recordingId,
							drafts.map { draft ->
								RecordingChapterModel(
									id = 0L,
									recordingId = recordingId,
									title = draft.title,
									startMs = draft.startMs,
									sourceSentenceIndex = draft.startSentenceIndex,
									createdAtMillis = now,
									transcriptRevision = revision,
								)
							},
						)
						possiblyStale = this@ChaptersViewModel.transcript?.updatedAtMillis != transcript.updatedAtMillis
						message = UiStrings.get(StringsRes.string.feature_player_generated_chapters, "已產生 %1\$s 個章節", drafts.size)
					}
					is CallOutcome.Failure -> message = UiStrings.get(StringsRes.string.feature_player_could_not_generate_chapters, "產生章節失敗：%1\$s", outcome.message)
					CallOutcome.ContentFiltered -> message = UiStrings.get(StringsRes.string.feature_player_chapters_filtered_by_service_nothing_was_saved, "章節內容被服務過濾，未儲存")
					CallOutcome.Empty -> message = UiStrings.get(StringsRes.string.feature_player_empty_response_nothing_was_saved_alt, "服務回應為空，未儲存")
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				message = UiStrings.get(StringsRes.string.feature_player_could_not_generate_chapters, "產生章節失敗：%1\$s", e.message ?: e.javaClass.simpleName)
			} finally {
				generating = false
			}
		}
	}

	/** Cancelling keeps the previous chapters and stores nothing from the cancelled run. */
	fun cancelGeneration() {
		job?.cancel()
		job = null
		generating = false
		message = UiStrings.get(StringsRes.string.feature_player_canceled_no_chapters_were_saved, "已取消，未儲存任何章節")
	}

	fun rename(chapter: RecordingChapterModel, title: String) {
		viewModelScope.launch { provider.rename(chapter, title) }
	}

	fun delete(chapter: RecordingChapterModel) {
		viewModelScope.launch { provider.delete(chapter) }
	}
}
