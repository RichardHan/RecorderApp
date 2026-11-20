package com.eva.feature_recordings.search.ask

import com.eva.feature_recordings.R as StringsRes
import com.eva.utils.UiStrings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.Redactor
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.feature_recordings.search.transcript.TranscriptSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the user must see before anything is sent. */
internal data class AskPlan(val sources: List<AskSource>, val chars: Int, val host: String, val model: String)

@HiltViewModel
internal class AskAcrossViewModel @Inject constructor(
	private val transcripts: TranscriptSearchRepository,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
) : ViewModel() {
	var plan by mutableStateOf<AskPlan?>(null); private set
	var answer by mutableStateOf<String?>(null); private set
	var message by mutableStateOf<String?>(null); private set
	var busy by mutableStateOf(false); private set
	private var job: Job? = null

	/** Step 1 (local only): gather sources and show the cost confirmation. */
	fun prepare(candidates: List<Pair<Long, String>>) {
		job?.cancel()
		answer = null; message = null; plan = null
		job = viewModelScope.launch {
			busy = true
			try {
				val s = settingsRepo.current()
				if (s.baseUrl.isBlank() || s.summaryModel.isBlank() || !keys.hasKey.value) {
					message = UiStrings.get(StringsRes.string.feature_recordings_set_a_cloud_summary_service_and_api, "尚未設定雲端摘要服務或 API Key"); return@launch
				}
				val sources = withContext(Dispatchers.IO) {
					AskAcross.pick(candidates) { id -> transcripts.lines(id)?.joinToString("\n") { it.text } }
				}
				if (sources.isEmpty()) { message = UiStrings.get(StringsRes.string.feature_recordings_these_results_have_no_usable_transcripts, "這些搜尋結果沒有可用的逐字稿"); return@launch }
				plan = AskPlan(sources, AskAcross.sentChars(sources), Redactor.hostOf(s.baseUrl) ?: s.baseUrl, s.summaryModel)
			} finally { busy = false }
		}
	}

	/** Step 2: exactly one request, only after the user confirmed [plan]. No automatic retry. */
	fun ask(question: String) {
		val p = plan ?: return
		if (question.isBlank()) return
		job?.cancel()
		answer = null; message = null
		job = viewModelScope.launch {
			busy = true
			try {
				val s = settingsRepo.current()
				val key = keys.read()
				if (key.isNullOrBlank()) { message = UiStrings.get(StringsRes.string.feature_recordings_api_key_missing, "尚未設定 API Key"); return@launch }
				val client = CloudAiClients.create(s, key, UrlConnectionTransport(), s.summaryModel)
				when (val out = withContext(Dispatchers.IO) { client.complete(AskAcross.prompt(question, p.sources)) }) {
					is CallOutcome.Text -> answer = out.text.trim()
					CallOutcome.Empty -> message = UiStrings.get(StringsRes.string.feature_recordings_the_service_returned_no_content, "服務沒有回傳內容")
					CallOutcome.ContentFiltered -> message = UiStrings.get(StringsRes.string.feature_recordings_the_service_blocked_the_answer_under_its, "回答被服務的內容政策擋下")
					is CallOutcome.Failure -> message = UiStrings.get(StringsRes.string.feature_recordings_question_failed, "提問失敗：%1\$s", out.message)
				}
			} catch (e: CancellationException) { throw e } catch (e: Exception) {
				message = UiStrings.get(StringsRes.string.feature_recordings_question_failed, "提問失敗：%1\$s", e.message ?: e::class.simpleName)
			} finally { busy = false }
		}
	}

	fun cancel() { job?.cancel(); busy = false }

	fun clearAnswer() { answer = null; message = null }

	fun reset() { cancel(); plan = null; answer = null; message = null }
}
