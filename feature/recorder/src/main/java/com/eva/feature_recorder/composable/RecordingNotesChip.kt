package com.eva.feature_recorder.composable

import com.eva.feature_recorder.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eva.cloudtranscribe.notes.RecordingNotes
import com.eva.cloudtranscribe.notes.RecordingNotesDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
internal class RecordingNotesViewModel @Inject constructor(private val draft: RecordingNotesDraft) : ViewModel() {
	val text = draft.text
	val leftover = draft.leftover
	fun save(text: String) = draft.update(text)
	fun applyLeftover() = draft.applyLeftover()
	fun discardLeftover() = draft.discardLeftover()
}

/**
 * One-line "備註" chip on the recorder screen (≥ 48dp touch target via the chip). Notes written before or during a
 * recording are bound to it when it is saved. They are only ever sent inside a cloud summary the user enabled.
 */
@Composable
internal fun RecordingNotesChip(viewModel: RecordingNotesViewModel = hiltViewModel()) {
	val text by viewModel.text.collectAsStateWithLifecycle()
	val leftover by viewModel.leftover.collectAsStateWithLifecycle()
	var editing by remember { mutableStateOf(false) }

	AssistChip(
		onClick = { editing = true },
		label = { Text(if (text.isBlank()) UiStrings.get(StringsRes.string.feature_recorder_notes, "備註") else UiStrings.get(StringsRes.string.feature_recorder_notes_characters, "備註（%1\$s 字）", text.length)) },
		modifier = Modifier.heightIn(min = 48.dp),
	)

	if (editing) {
		var draft by remember { mutableStateOf(text) }
		AlertDialog(
			onDismissRequest = { editing = false },
			title = { Text(stringResource(StringsRes.string.feature_recorder_recording_notes)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(
						value = draft,
						onValueChange = { draft = it.take(RecordingNotes.MAX_CHARS) },
						label = { Text(stringResource(StringsRes.string.feature_recorder_agenda_names_or_background_up_to_characters, (RecordingNotes.MAX_CHARS).toString())) },
						minLines = 3,
						modifier = Modifier.fillMaxWidth(),
					)
					Text(
						stringResource(StringsRes.string.feature_recorder_notes_are_linked_to_the_saved_recording),
						style = MaterialTheme.typography.bodySmall,
					)
				}
			},
			confirmButton = { TextButton({ viewModel.save(draft); editing = false }) { Text(stringResource(StringsRes.string.feature_recorder_save)) } },
			dismissButton = { TextButton({ editing = false }) { Text(stringResource(StringsRes.string.feature_recorder_cancel)) } },
		)
	}

	leftover?.let { note ->
		AlertDialog(
			onDismissRequest = {},
			title = { Text(stringResource(StringsRes.string.feature_recorder_unsaved_notes_from_last_time)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(stringResource(StringsRes.string.feature_recorder_notes_from_the_last_session_have_no))
					Text(note.text.take(300) + if (note.text.length > 300) "…" else "", style = MaterialTheme.typography.bodySmall)
				}
			},
			confirmButton = { TextButton(viewModel::applyLeftover) { Text(stringResource(StringsRes.string.feature_recorder_apply)) } },
			dismissButton = { TextButton(viewModel::discardLeftover) { Text(stringResource(StringsRes.string.feature_recorder_discard)) } },
		)
	}
}
