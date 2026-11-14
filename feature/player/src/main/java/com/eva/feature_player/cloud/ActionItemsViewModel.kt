package com.eva.feature_player.cloud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.SummaryActionItems
import com.eva.recordings.domain.models.RecordingActionItemModel
import com.eva.recordings.domain.provider.RecordingActionItemsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * State of the to-do card of one recording. Everything is local: the list is only filled when the user confirms the
 * summary bullets they want, or types an item themselves. Nothing here calls a model or a network service.
 */
@HiltViewModel
class ActionItemsViewModel @Inject constructor(
	private val provider: RecordingActionItemsProvider,
) : ViewModel() {

	var items by mutableStateOf<List<RecordingActionItemModel>>(emptyList())
		private set

	var expanded by mutableStateOf(false)
		private set

	/** Candidate bullets of the current summary, only set while the multi-select dialog is open. */
	var candidates by mutableStateOf<List<String>>(emptyList())
		private set

	var selected by mutableStateOf<Set<String>>(emptySet())
		private set

	var message by mutableStateOf<String?>(null)
		private set

	var editingId by mutableStateOf<Long?>(null)
		private set

	private var recordingId = -1L
	private var bound = false

	/** Oldest item time of this recording, used to tell whether the summary is newer than the to-do list. */
	val listStartedAtMillis: Long? get() = items.minOfOrNull { it.createdAtMillis }

	fun bind(id: Long) {
		if (bound && recordingId == id) return
		recordingId = id
		bound = true
		viewModelScope.launch {
			provider.actionItemsAsFlow(id)
				.map { list -> list }
				.catch { message = "無法讀取待辦：${it.message ?: "未知錯誤"}"; emit(emptyList()) }
				.collect { items = it }
		}
	}

	fun onExpandedChange(value: Boolean) { expanded = value }

	fun dismissMessage() { message = null }

	/** Asks the user which summary bullets to turn into items; nothing is written before [confirmAddSelected]. */
	fun startPicking(summaryText: String) {
		val existing = items.map { it.text }
		candidates = SummaryActionItems.candidates(summaryText)
			.filterNot { candidate -> existing.any { SummaryActionItems.normalize(it) == SummaryActionItems.normalize(candidate) } }
		selected = emptySet()
		if (candidates.isEmpty()) message = "這份摘要沒有可加入的待辦條目"
	}

	fun toggleCandidate(candidate: String) {
		selected = if (candidate in selected) selected - candidate else selected + candidate
	}

	fun cancelPicking() { candidates = emptyList(); selected = emptySet() }

	@OptIn(ExperimentalTime::class)
	fun confirmAddSelected(source: String = RecordingActionItemModel.SOURCE_SUMMARY) {
		val picked = candidates.filter { it in selected }
		candidates = emptyList()
		selected = emptySet()
		if (picked.isEmpty() || recordingId <= 0) return
		viewModelScope.launch {
			val added = provider.addItems(recordingId, picked, source, Clock.System.now().toEpochMilliseconds())
			message = if (added.isEmpty()) "沒有新增待辦（可能已存在）" else "已加入 ${added.size} 項待辦"
		}
	}

	@OptIn(ExperimentalTime::class)
	fun addManual(text: String) {
		val trimmed = text.trim()
		if (trimmed.isEmpty() || recordingId <= 0) return
		viewModelScope.launch {
			val added = provider.addItems(
				recordingId,
				listOf(trimmed),
				RecordingActionItemModel.SOURCE_MANUAL,
				Clock.System.now().toEpochMilliseconds(),
			)
			message = if (added.isEmpty()) "這個待辦已存在" else null
		}
	}

	fun startEditing(id: Long) { editingId = id }
	fun stopEditing() { editingId = null }

	fun saveText(item: RecordingActionItemModel, text: String) {
		editingId = null
		if (text.trim() == item.text.trim() || text.isBlank()) return
		viewModelScope.launch { provider.updateItem(item.copy(text = text)) }
	}

	fun setDone(item: RecordingActionItemModel, done: Boolean) {
		viewModelScope.launch { provider.updateItem(item.copy(isDone = done)) }
	}

	fun delete(item: RecordingActionItemModel) {
		viewModelScope.launch { provider.deleteItem(item) }
	}

	/** Text for the clipboard: only unfinished items, fixed `- [ ] ` lines. */
	fun unfinishedClipboardText(): String =
		SummaryActionItems.unfinishedAsClipboardText(items.map { it.text to it.isDone })
}
