package com.eva.feature_recorder.composable

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
		label = { Text(if (text.isBlank()) "備註" else "備註（${text.length} 字）") },
		modifier = Modifier.heightIn(min = 48.dp),
	)

	if (editing) {
		var draft by remember { mutableStateOf(text) }
		AlertDialog(
			onDismissRequest = { editing = false },
			title = { Text("這段錄音的備註") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(
						value = draft,
						onValueChange = { draft = it.take(RecordingNotes.MAX_CHARS) },
						label = { Text("議程、人名、背景（最多 ${RecordingNotes.MAX_CHARS} 字）") },
						minLines = 3,
						modifier = Modifier.fillMaxWidth(),
					)
					Text(
						"存檔時會綁到這段錄音；取消錄音會一起捨棄。只有在你已開啟的雲端重點／摘要裡才會送出，並標註為「只供參考」。",
						style = MaterialTheme.typography.bodySmall,
					)
				}
			},
			confirmButton = { TextButton({ viewModel.save(draft); editing = false }) { Text("儲存") } },
			dismissButton = { TextButton({ editing = false }) { Text("取消") } },
		)
	}

	leftover?.let { note ->
		AlertDialog(
			onDismissRequest = {},
			title = { Text("上次有未綁定的備註") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text("App 上次關閉時，還有一份沒有綁到任何錄音的備註。要套用到下一段（或目前這段）錄音，還是捨棄？")
					Text(note.text.take(300) + if (note.text.length > 300) "…" else "", style = MaterialTheme.typography.bodySmall)
				}
			},
			confirmButton = { TextButton(viewModel::applyLeftover) { Text("套用") } },
			dismissButton = { TextButton(viewModel::discardLeftover) { Text("捨棄") } },
		)
	}
}
