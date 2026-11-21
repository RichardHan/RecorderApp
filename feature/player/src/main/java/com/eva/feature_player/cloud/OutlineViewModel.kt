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
import com.eva.cloudtranscribe.outline.OutlineNode
import com.eva.cloudtranscribe.outline.SavedOutline
import com.eva.cloudtranscribe.outline.SummaryOutline
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

/** Which outline is on screen. */
enum class OutlineMode { LOCAL, AI }

/**
 * Hierarchical outline of the newest summary. The local outline is free and always derived on the fly; the AI outline
 * is created only after the user confirms, sends just the summary text once, and is kept only when fully valid.
 */
@HiltViewModel
class OutlineViewModel @Inject constructor(
	private val store: TranscriptStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) : ViewModel() {

	var saved by mutableStateOf<SavedOutline?>(null)
		private set
	var working by mutableStateOf(false)
		private set
	var message by mutableStateOf<String?>(null)
		private set

	private var recordingId = -1L
	private var job: Job? = null

	fun bind(id: Long) {
		if (recordingId == id) return
		recordingId = id
		viewModelScope.launch { saved = withContext(Dispatchers.IO) { runCatching { store.outline(id) }.getOrNull() } }
	}

	fun dismissMessage() { message = null }

	fun organise(summaryId: String, summary: String) {
		if (working || SummaryOutline.isEmptySummary(summary) || recordingId < 0) return
		val id = recordingId
		job = viewModelScope.launch {
			working = true
			message = null
			try {
				val settings = settingsRepo.current()
				val key = keys.read()
				if (settings.baseUrl.isBlank() || key.isNullOrBlank()) {
					message = UiStrings.get(StringsRes.string.feature_player_set_a_cloud_summary_service_and_api, "尚未設定雲端摘要服務或 API Key")
					return@launch
				}
				val client = CloudAiClients.create(settings, key, UrlConnectionTransport(), settings.summaryModel)
				when (val outcome = withContext(Dispatchers.IO) { client.complete(SummaryOutline.prompt(summary)) }) {
					is CallOutcome.Text -> {
						val nodes = SummaryOutline.parseAi(outcome.text)
						if (nodes == null) {
							message = UiStrings.get(StringsRes.string.feature_player_outline_exceeded_the_size_or_depth_limit, "回應不是有效的大綱（超過兩層、節點過多或格式錯誤），保留本機大綱")
							return@launch
						}
						val outline = OutlineRules.save(summaryId, summary, nodes, System.currentTimeMillis(), settings.summaryModel)
						withContext(Dispatchers.IO) { store.saveOutline(id, outline) }
						saved = outline
						message = UiStrings.get(StringsRes.string.feature_player_outline_ready, "已整理成大綱")
					}
					is CallOutcome.Failure -> message = UiStrings.get(StringsRes.string.feature_player_could_not_organize_local_outline_kept, "整理失敗：%1\$s（保留本機大綱）", outcome.message)
					CallOutcome.ContentFiltered -> message = UiStrings.get(StringsRes.string.feature_player_content_filtered_local_outline_kept, "內容被服務過濾，保留本機大綱")
					CallOutcome.Empty -> message = UiStrings.get(StringsRes.string.feature_player_empty_response_local_outline_kept, "服務回應為空，保留本機大綱")
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				message = UiStrings.get(StringsRes.string.feature_player_could_not_organize, "整理失敗：%1\$s", e.message ?: e.javaClass.simpleName)
			} finally {
				working = false
			}
		}
	}

	fun cancel() {
		job?.cancel()
		job = null
		working = false
		message = UiStrings.get(StringsRes.string.feature_player_canceled_local_outline_kept, "已取消，保留本機大綱")
	}

	/** "回復本機大綱": drop the AI outline. */
	fun restoreLocal() {
		val id = recordingId
		if (id < 0) return
		viewModelScope.launch {
			withContext(Dispatchers.IO) { store.deleteOutline(id) }
			saved = null
		}
	}
}

/** Pure rules, testable without Android. */
internal object OutlineRules {

	fun save(summaryId: String, summary: String, nodes: List<OutlineNode>, now: Long, model: String?) =
		SavedOutline(summaryId, SummaryOutline.hash(summary), nodes, now, model?.takeIf { it.isNotBlank() })

	/** An AI outline is stale when the newest summary is a different one or its text changed. */
	fun isStale(saved: SavedOutline?, summaryId: String?, summary: String?): Boolean =
		saved != null && (summaryId == null || summary == null || saved.summaryId != summaryId || saved.summaryHash != SummaryOutline.hash(summary))

	fun display(mode: OutlineMode, saved: SavedOutline?, summary: String?): List<OutlineNode> =
		if (mode == OutlineMode.AI && saved != null) saved.nodes else SummaryOutline.local(summary)
}
