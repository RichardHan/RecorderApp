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
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.cloudtranscribe.translate.SavedTranslation
import com.eva.cloudtranscribe.translate.TranscriptTranslation
import com.eva.cloudtranscribe.translate.TranslatedLine
import com.eva.cloudtranscribe.translate.TranslationLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual, optional transcript translation. One text request per chunk of numbered sentences, only after the user
 * confirms; a translation is saved only when every chunk came back complete and aligned (otherwise nothing changes).
 */
@HiltViewModel
class TranslationViewModel @Inject constructor(
	private val store: TranscriptStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) : ViewModel() {

	var translations by mutableStateOf<List<SavedTranslation>>(emptyList())
		private set
	var working by mutableStateOf(false)
		private set
	var progress by mutableStateOf<Pair<Int, Int>?>(null)
		private set
	var message by mutableStateOf<String?>(null)
		private set

	private var recordingId = -1L
	private var job: Job? = null

	fun bind(id: Long) {
		if (recordingId == id) return
		recordingId = id
		viewModelScope.launch { translations = withContext(Dispatchers.IO) { runCatching { store.translations(id) }.getOrDefault(emptyList()) } }
	}

	fun dismissMessage() { message = null }

	fun translate(record: TranscriptRecord, language: TranslationLanguage) {
		if (working || TranslationRules.blockedReason(record) != null) return
		val sentences = TranscriptTranslation.sentences(PlaybackTranscripts.lines(record))
		val chunks = TranscriptTranslation.chunks(sentences)
		val id = recordingId
		job = viewModelScope.launch {
			working = true
			message = null
			progress = 0 to chunks.size
			try {
				val settings = settingsRepo.current()
				val key = keys.read()
				if (settings.baseUrl.isBlank() || key.isNullOrBlank()) {
					message = UiStrings.get(StringsRes.string.feature_player_set_a_cloud_summary_service_and_api, "尚未設定雲端摘要服務或 API Key")
					return@launch
				}
				val client = CloudAiClients.create(settings, key, UrlConnectionTransport(), settings.summaryModel)
				val lines = TranslationRules.translateAll(chunks, language) { prompt -> withContext(Dispatchers.IO) { client.complete(prompt) } }
					.also { if (it is TranslationRules.Result.Failed) { message = it.reason; return@launch } }
					.let { (it as TranslationRules.Result.Done).lines }
				val saved = SavedTranslation(
					recordingId = id,
					transcriptId = record.id,
					targetLang = language.tag,
					sourceRevision = TranscriptTranslation.revisionOf(record.id, record.updatedAtMillis),
					lines = lines,
					createdAtMillis = System.currentTimeMillis(),
					model = settings.summaryModel.takeIf { it.isNotBlank() },
				)
				withContext(Dispatchers.IO) { store.saveTranslation(saved) }
				translations = listOf(saved) + translations.filterNot { it.targetLang == saved.targetLang }
				message = UiStrings.get(StringsRes.string.feature_player_translated_into, "已翻譯成%1\$s", language.label)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				message = UiStrings.get(StringsRes.string.feature_player_translation_failed_nothing_was_saved, "翻譯失敗：%1\$s，未儲存", e.message ?: e.javaClass.simpleName)
			} finally {
				working = false
				progress = null
			}
		}
	}

	fun cancel() {
		job?.cancel()
		job = null
		working = false
		progress = null
		message = UiStrings.get(StringsRes.string.feature_player_canceled_no_translation_was_saved, "已取消，未儲存任何譯文")
	}

	fun delete(language: String) {
		val id = recordingId
		viewModelScope.launch {
			withContext(Dispatchers.IO) { store.deleteTranslation(id, language) }
			translations = translations.filterNot { it.targetLang == language }
		}
	}
}

/** Pure rules, testable without Android or network. */
internal object TranslationRules {

	sealed interface Result {
		data class Done(val lines: List<TranslatedLine>) : Result
		data class Failed(val reason: String) : Result
	}

	fun blockedReason(record: TranscriptRecord?): String? = when {
		record == null -> UiStrings.get(StringsRes.string.feature_player_no_transcript_to_translate, "沒有可翻譯的逐字稿")
		record.status == RunStatus.RUNNING || record.status == RunStatus.QUEUED -> UiStrings.get(StringsRes.string.feature_player_wait_for_transcription_to_finish_before_translating, "逐字稿還在轉錄中，完成後才能翻譯")
		TranscriptTranslation.sentences(PlaybackTranscripts.lines(record)).isEmpty() -> UiStrings.get(StringsRes.string.feature_player_the_transcript_is_empty, "逐字稿是空的")
		else -> null
	}

	/** Sends chunks one by one; any failure or misaligned chunk fails the whole run (nothing partial is kept). */
	suspend fun translateAll(
		chunks: List<List<com.eva.cloudtranscribe.translate.TranslationSentence>>,
		language: TranslationLanguage,
		complete: suspend (String) -> CallOutcome,
	): Result {
		val all = mutableListOf<TranslatedLine>()
		chunks.forEachIndexed { index, chunk ->
			val part = UiStrings.get(StringsRes.string.feature_player_segment_of, "第 %1\$s/%2\$s 段", index + 1, chunks.size)
			when (val outcome = complete(TranscriptTranslation.prompt(chunk, language))) {
				is CallOutcome.Text -> all += TranscriptTranslation.parse(outcome.text, chunk)
					?: return Result.Failed(UiStrings.get(StringsRes.string.feature_player_returned_missing_or_extra_sentences_no_translation, "%1\$s 的回應沒有逐句對齊（缺少或多出句子），未儲存任何譯文", part))
				is CallOutcome.Failure -> return Result.Failed(UiStrings.get(StringsRes.string.feature_player_translation_failed_for_nothing_was_saved, "%1\$s 翻譯失敗：%2\$s，未儲存", part, outcome.message))
				CallOutcome.ContentFiltered -> return Result.Failed(UiStrings.get(StringsRes.string.feature_player_the_service_filtered_nothing_was_saved, "%1\$s 被服務過濾，未儲存", part))
				CallOutcome.Empty -> return Result.Failed(UiStrings.get(StringsRes.string.feature_player_empty_response_for_nothing_was_saved, "%1\$s 回應為空，未儲存", part))
			}
		}
		return Result.Done(all)
	}

	/** Only a translation of this exact transcript is shown; [stale] when the transcript changed since. */
	fun forRecord(translations: List<SavedTranslation>, record: TranscriptRecord, language: String?): SavedTranslation? =
		translations.firstOrNull { it.transcriptId == record.id && (language == null || it.targetLang == language) }

	fun stale(saved: SavedTranslation, record: TranscriptRecord) = TranscriptTranslation.isStale(saved, record.id, record.updatedAtMillis)
}

