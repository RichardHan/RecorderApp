package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.notes.RecordingNote
import com.eva.cloudtranscribe.notes.RecordingNotes
import com.eva.cloudtranscribe.store.TranscriptStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class RecordingNotesViewModel @Inject constructor(private val store: TranscriptStore) : ViewModel() {
	var note by mutableStateOf<RecordingNote?>(null)
		private set
	var loaded by mutableStateOf(false)
		private set
	private var recordingId = -1L

	fun bind(id: Long) {
		if (recordingId == id) return
		recordingId = id
		viewModelScope.launch {
			note = withContext(Dispatchers.IO) { runCatching { store.notes(id) }.getOrNull() }
			loaded = true
		}
	}

	/** Only affects summaries generated after this edit. Blank text deletes the notes. */
	fun save(text: String) {
		val id = recordingId
		if (id < 0) return
		val cleaned = RecordingNotes.clean(text)
		viewModelScope.launch {
			withContext(Dispatchers.IO) {
				if (cleaned.isEmpty()) store.deleteNotes(id) else store.saveNotes(id, RecordingNote(cleaned, System.currentTimeMillis()))
			}
			note = cleaned.takeIf { it.isNotEmpty() }?.let { RecordingNote(it, System.currentTimeMillis()) }
		}
	}
}

/** Notes of this recording; editing them only changes summaries created afterwards. */
@Composable
internal fun RecordingNotesCard(recordingId: Long, viewModel: RecordingNotesViewModel = hiltViewModel(key = "notes-$recordingId")) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	if (!viewModel.loaded) return
	var editing by remember { mutableStateOf(false) }
	var draft by remember(viewModel.note) { mutableStateOf(viewModel.note?.text.orEmpty()) }
	Card {
		Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(stringResource(StringsRes.string.feature_player_recording_notes), style = MaterialTheme.typography.titleSmall)
			if (editing) {
				OutlinedTextField(draft, { draft = it.take(RecordingNotes.MAX_CHARS) }, minLines = 3, modifier = Modifier.fillMaxWidth())
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					TextButton({ viewModel.save(draft); editing = false }) { Text(stringResource(StringsRes.string.feature_player_save)) }
					TextButton({ draft = viewModel.note?.text.orEmpty(); editing = false }) { Text(stringResource(StringsRes.string.feature_player_cancel)) }
				}
			} else {
				Text(viewModel.note?.text ?: UiStrings.get(StringsRes.string.feature_player_no_notes, "（沒有備註）"), style = MaterialTheme.typography.bodySmall)
				TextButton({ editing = true }) { Text(if (viewModel.note == null) UiStrings.get(StringsRes.string.feature_player_add_notes, "新增備註") else UiStrings.get(StringsRes.string.feature_player_edit, "編輯")) }
			}
			Text(stringResource(StringsRes.string.feature_player_notes_are_sent_as_reference_when_a),
				style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}
