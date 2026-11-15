package com.eva.feature_player.cloud

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.getSystemService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.followup.FollowUpItem
import com.eva.cloudtranscribe.followup.FollowUpMaterials
import com.eva.cloudtranscribe.followup.FollowUpPlanner
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.FollowUpDraft
import com.eva.cloudtranscribe.store.FollowUpDraftStore
import com.eva.recordings.domain.provider.RecordingActionItemsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Follow-up draft of one recording. Generating sends only the accepted summary and stored to-dos (never audio or the
 * transcript) to the user's configured summary service, after the user confirms. The result is only ever copied or
 * handed to the system share sheet by the user; the app itself never sends it anywhere.
 */
@HiltViewModel
class FollowUpViewModel @Inject constructor(
	private val actionItems: RecordingActionItemsProvider,
	private val drafts: FollowUpDraftStore,
	private val settingsRepo: CloudSettingsRepository,
	private val keys: ApiKeyStore,
	@ApplicationContext private val context: Context,
) : ViewModel() {

	/** Text in the editor (may differ from the stored draft until the user saves). */
	var draftText by mutableStateOf("")
		private set

	var savedDraft by mutableStateOf<FollowUpDraft?>(null)
		private set

	var generating by mutableStateOf(false)
		private set

	var message by mutableStateOf<String?>(null)
		private set

	var materials by mutableStateOf<FollowUpMaterials?>(null)
		private set

	var instructions by mutableStateOf("")
		private set

	/** True when the stored draft was made from older summary / to-dos than the current ones. */
	val possiblyStale: Boolean
		get() {
			val saved = savedDraft ?: return false
			return saved.sourceRevision.isNotEmpty() && saved.sourceRevision != currentRevision
		}

	private var currentRevision = ""
	private var recordingId = -1L
	private var summaryId: String? = null
	private var job: Job? = null

	/** Re-evaluates the material whenever the accepted summary or the recording changes. */
	fun bind(id: Long, acceptedSummaryId: String?, acceptedSummaryText: String?) {
		recordingId = id
		summaryId = acceptedSummaryId
		viewModelScope.launch {
			val items = runCatching { actionItems.actionItems(id) }.getOrDefault(emptyList())
				.map { FollowUpItem(it.text, it.isDone) }
			val built = FollowUpPlanner.materials(acceptedSummaryText, items)
			materials = built
			currentRevision = FollowUpPlanner.revisionOf(acceptedSummaryId, built)
			val stored = withContext(Dispatchers.IO) { drafts.read(id) }
			savedDraft = stored
			if (draftText.isBlank()) draftText = stored?.draftText.orEmpty()
			instructions = runCatching { settingsRepo.current().followUpInstructions }.getOrDefault("")
		}
	}

	fun onDraftChange(text: String) {
		draftText = text
	}

	fun onInstructionsChange(text: String) {
		instructions = text.take(FollowUpPlanner.MAX_INSTRUCTIONS_CHARS)
	}

	fun dismissMessage() {
		message = null
	}

	/** Only one generation at a time; a second request is ignored while one runs. */
	fun generate() {
		if (generating) return
		val material = materials ?: run {
			message = "請先產生並接受摘要，或加入待辦，才能寫跟催草稿"
			return
		}
		val revision = currentRevision
		val style = instructions
		job = viewModelScope.launch {
			generating = true
			message = null
			try {
				settingsRepo.update { it.copy(followUpInstructions = style.trim()) }
				val settings = settingsRepo.current()
				val key = keys.read()
				if (settings.baseUrl.isBlank() || key.isNullOrBlank()) {
					message = "尚未設定雲端摘要服務或 API Key"
					return@launch
				}
				val client = CloudAiClients.create(settings, key, UrlConnectionTransport(), settings.summaryModel)
				val prompt = FollowUpPlanner.prompt(material, style)
				val outcome = withContext(Dispatchers.IO) { client.complete(prompt) }
				when (outcome) {
					is CallOutcome.Text -> {
						val accepted = FollowUpPlanner.accept(outcome.text)
						if (accepted == null) {
							message = "服務沒有回傳可用的草稿，原本的草稿保留不變"
							return@launch
						}
						val draft = FollowUpDraft(recordingId, accepted, System.currentTimeMillis(), revision)
						withContext(Dispatchers.IO) { drafts.save(draft) }
						savedDraft = draft
						draftText = accepted
						message = "已產生草稿。請核對內容後再複製或分享；App 不會自動寄出。"
					}

					is CallOutcome.Failure -> message = "產生失敗：${outcome.message}"
					CallOutcome.ContentFiltered -> message = "內容被服務過濾，原本的草稿保留不變"
					CallOutcome.Empty -> message = "服務回應為空，原本的草稿保留不變"
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				message = "產生失敗：${e.message ?: e.javaClass.simpleName}"
			} finally {
				generating = false
			}
		}
	}

	/** Cancelling never writes or overwrites the stored draft. */
	fun cancel() {
		job?.cancel()
		job = null
		generating = false
		message = "已取消，草稿沒有變更"
	}

	fun saveLocally() {
		val text = draftText.trim()
		if (text.isEmpty()) return
		val draft = FollowUpDraft(recordingId, text, System.currentTimeMillis(), savedDraft?.sourceRevision ?: currentRevision)
		viewModelScope.launch {
			withContext(Dispatchers.IO) { drafts.save(draft) }
			savedDraft = draft
			message = "草稿已存在本機"
		}
	}

	fun discard() {
		viewModelScope.launch {
			withContext(Dispatchers.IO) { drafts.delete(recordingId) }
			savedDraft = null
			draftText = ""
			message = "已丟棄草稿"
		}
	}

	/** Copies exactly the edited text. */
	fun copy() {
		val text = draftText.trim()
		if (text.isEmpty()) return
		val clipboard = context.getSystemService<ClipboardManager>() ?: return
		clipboard.setPrimaryClip(ClipData.newPlainText("跟催草稿", text))
		message = "已複製（尚未寄出，請自行貼到要傳的地方）"
	}

	/** Opens the system share sheet; the user picks the app and sends it there themselves. */
	fun share() {
		val text = draftText.trim()
		if (text.isEmpty()) return
		val send = Intent(Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(Intent.EXTRA_TEXT, text)
		}
		val chooser = Intent.createChooser(send, "分享跟催草稿").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
		try {
			context.startActivity(chooser)
		} catch (_: ActivityNotFoundException) {
			message = "沒有可分享的應用程式"
		}
	}
}
