package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClient
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.cleanup.CleanedSentence
import com.eva.cloudtranscribe.cleanup.CleanupIntensity
import com.eva.cloudtranscribe.cleanup.CleanupRecord
import com.eva.cloudtranscribe.cleanup.CleanupSentence
import com.eva.cloudtranscribe.cleanup.TranscriptCleanup
import com.eva.cloudtranscribe.cleanup.TranscriptCleanupStore
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Builds the network client; replaced in tests so no real service is ever called. */
fun interface CleanupClientFactory {
	fun create(settings: CloudSettings, apiKey: String): CloudAiClient
}

/**
 * Manual "清稿" for one recording. Sends transcript text (never audio) to the user's configured summary service only
 * after confirmation, stores the result next to the transcript without touching it, and keeps nothing from a failed,
 * rejected or cancelled run.
 */
@HiltViewModel
class CleanupViewModel internal constructor(
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
	private val store: TranscriptStore,
	private val cleanups: TranscriptCleanupStore,
	private val clientFactory: CleanupClientFactory,
) : ViewModel() {

	@Inject constructor(
		settingsRepo: CloudSettingsRepository,
		keys: ApiKeyStore,
		store: TranscriptStore,
		cleanups: TranscriptCleanupStore,
	) : this(settingsRepo, keys, store, cleanups, CleanupClientFactory { s, key -> CloudAiClients.create(s, key, UrlConnectionTransport(), s.summaryModel) })

	var intensity by mutableStateOf(CleanupIntensity.LOW)
		private set
	var cleanup by mutableStateOf<CleanupRecord?>(null)
		private set
	/** Original sentences (with their real times) of the transcript the cleanup refers to. */
	var sentences by mutableStateOf<List<CleanupSentence>>(emptyList())
		private set
	var possiblyStale by mutableStateOf(false)
		private set
	var generating by mutableStateOf(false)
		private set
	var progress by mutableStateOf<Pair<Int, Int>?>(null)
		private set
	var message by mutableStateOf<String?>(null)
		private set
	var blockedReason by mutableStateOf<String?>(null)
		private set
	/** Requests the next run would make; shown in the confirmation dialog. */
	val requestCount: Int get() = TranscriptCleanup.batches(sentences).size
	val characterCount: Int get() = sentences.sumOf { it.text.length }

	private var transcript: TranscriptRecord? = null
	private var recordingId = -1L
	private var sourceJob: Job? = null
	private var job: Job? = null

	fun bind(id: Long, force: Boolean = false) {
		if (!force && recordingId == id) return
		recordingId = id
		sourceJob?.cancel()
		sourceJob = viewModelScope.launch { store.editChanges.collect { reload() } }
	}

	fun selectIntensity(value: CleanupIntensity) {
		if (generating || value == intensity) return
		intensity = value
		viewModelScope.launch { reload() }
	}

	private suspend fun reload() {
		val id = recordingId
		val level = intensity
		withContext(Dispatchers.IO) {
			val selected = PlaybackTranscripts.defaultSelection(store.transcriptsWithEdits(id))
			transcript = selected
			sentences = selected?.let { TranscriptCleanup.sentences(PlaybackTranscripts.lines(it)) }.orEmpty()
			val stored = cleanups.cleanup(id, level)
			cleanup = stored
			possiblyStale = stored != null && (selected == null || stored.sourceRevision != TranscriptCleanup.revisionOf(selected.id, selected.updatedAtMillis))
			val settings = settingsRepo.current()
			blockedReason = when {
				selected == null || sentences.isEmpty() -> UiStrings.get(StringsRes.string.feature_player_no_transcript_available_to_clean, "這段錄音還沒有可清稿的逐字稿")
				settings.baseUrl.isBlank() || keys.read().isNullOrBlank() -> UiStrings.get(StringsRes.string.feature_player_configure_the_summary_service_and_api_key, "尚未設定雲端摘要服務或 API Key（設定 > 雲端）")
				else -> null
			}
		}
	}

	fun dismissMessage() { message = null }

	fun generate() {
		if (generating || blockedReason != null) return
		val source = transcript ?: return
		val batches = TranscriptCleanup.batches(sentences)
		if (batches.isEmpty()) return
		val level = intensity
		val id = recordingId
		job?.cancel()
		job = viewModelScope.launch {
			generating = true
			message = null
			try {
				val settings = settingsRepo.current()
				val key = keys.read()
				if (settings.baseUrl.isBlank() || key.isNullOrBlank()) { message = UiStrings.get(StringsRes.string.feature_player_set_a_cloud_summary_service_and_api, "尚未設定雲端摘要服務或 API Key"); return@launch }
				val client = clientFactory.create(settings, key)
				val cleaned = mutableListOf<CleanedSentence>()
				for ((i, batch) in batches.withIndex()) {
					progress = i to batches.size
					val outcome = withContext(Dispatchers.IO) { client.complete(TranscriptCleanup.prompt(batch, level)) }
					val parsed = when (outcome) {
						is CallOutcome.Text -> TranscriptCleanup.parse(outcome.text, batch, level)
							?: run { message = UiStrings.get(StringsRes.string.feature_player_part_could_not_be_matched_to_its, "第 %1\$s 段回應對不上原句（編號不符或內容變長），整次未儲存", i + 1); return@launch }
						is CallOutcome.Failure -> { message = UiStrings.get(StringsRes.string.feature_player_cleanup_failed_nothing_was_saved, "清稿失敗：%1\$s，整次未儲存", outcome.message); return@launch }
						CallOutcome.ContentFiltered -> { message = UiStrings.get(StringsRes.string.feature_player_content_filtered_nothing_was_saved, "內容被服務過濾，整次未儲存"); return@launch }
						CallOutcome.Empty -> { message = UiStrings.get(StringsRes.string.feature_player_empty_response_nothing_was_saved, "服務回應為空，整次未儲存"); return@launch }
					}
					cleaned += parsed
				}
				val record = CleanupRecord(
					recordingId = id, transcriptId = source.id,
					sourceRevision = TranscriptCleanup.revisionOf(source.id, source.updatedAtMillis),
					intensity = level, model = settings.summaryModel, createdAtMillis = System.currentTimeMillis(), sentences = cleaned,
				)
				withContext(Dispatchers.IO) { cleanups.save(record) }
				if (recordingId == id && intensity == level) { cleanup = record; possiblyStale = transcript?.updatedAtMillis != source.updatedAtMillis }
				message = UiStrings.get(StringsRes.string.feature_player_cleaned_copy_saved_original_transcript_kept, "清稿完成（原逐字稿未變更）")
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				message = UiStrings.get(StringsRes.string.feature_player_cleanup_failed_nothing_was_saved, "清稿失敗：%1\$s，整次未儲存", e.message ?: e.javaClass.simpleName)
			} finally {
				generating = false
				progress = null
			}
		}
	}

	fun cancel() {
		job?.cancel()
		job = null
		generating = false
		progress = null
		message = UiStrings.get(StringsRes.string.feature_player_canceled_no_cleaned_copy_was_saved, "已取消，未儲存任何清稿")
	}

	fun delete() {
		val id = recordingId
		val level = intensity
		viewModelScope.launch {
			withContext(Dispatchers.IO) { cleanups.delete(id, level) }
			cleanup = null
			possiblyStale = false
			message = UiStrings.get(StringsRes.string.feature_player_cleaned_copy_deleted_original_transcript_kept, "已刪除清稿（原逐字稿不受影響）")
		}
	}
}
