package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.eva.cloudtranscribe.qa.QaRecord
import com.eva.cloudtranscribe.qa.RecordingQa

/** "問這段錄音": nothing is sent until the user types a question, presses send and (first time here) confirms. */
@Composable
internal fun RecordingQaCard(
	recordingId: Long,
	onSeekTo: ((Long) -> Unit)?,
	viewModel: RecordingQaViewModel = hiltViewModel(key = "qa-$recordingId"),
) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	var question by rememberSaveable(recordingId) { mutableStateOf("") }
	var confirmed by rememberSaveable(recordingId) { mutableStateOf(false) }
	var confirm by remember { mutableStateOf(false) }

	Card {
		Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(stringResource(StringsRes.string.feature_player_ask_about_this_recording), style = MaterialTheme.typography.titleSmall)
			OutlinedTextField(
				value = question,
				onValueChange = { question = it.take(RecordingQa.MAX_QUESTION_CHARS) },
				label = { Text(stringResource(StringsRes.string.feature_player_for_example_when_is_the_quote_due)) },
				enabled = viewModel.blockedReason == null && !viewModel.asking,
				modifier = Modifier.fillMaxWidth(),
			)
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Button(
					enabled = viewModel.blockedReason == null && !viewModel.asking && question.isNotBlank(),
					onClick = { if (confirmed) viewModel.ask(question) else confirm = true },
				) { Text(if (viewModel.asking) UiStrings.get(StringsRes.string.feature_player_answering, "回答中…") else UiStrings.get(StringsRes.string.feature_player_send, "送出")) }
				if (viewModel.asking) TextButton(onClick = viewModel::cancel) { Text(stringResource(StringsRes.string.feature_player_cancel)) }
			}
			viewModel.blockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
			viewModel.message?.let { msg ->
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
					TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(StringsRes.string.feature_player_got_it)) }
				}
			}
			viewModel.history.forEach { record -> QaAnswer(record, onSeekTo, onDelete = { viewModel.delete(record) }) }
		}
	}

	if (confirm) AlertDialog(
		onDismissRequest = { confirm = false },
		title = { Text(stringResource(StringsRes.string.feature_player_send_question)) },
		text = {
			Text(stringResource(StringsRes.string.feature_player_each_question_sends_this_transcript_to_the, (viewModel.scope ?: UiStrings.get(StringsRes.string.feature_player_transcript, "逐字稿")).toString()) +
				UiStrings.get(StringsRes.string.feature_player_only_text_is_sent_the_answer_uses, "不會送音訊。回答只根據逐字稿，找不到會直接說找不到。"))
		},
		confirmButton = { TextButton({ confirm = false; confirmed = true; viewModel.ask(question) }) { Text(stringResource(StringsRes.string.feature_player_send)) } },
		dismissButton = { TextButton({ confirm = false }) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
	)
}

@Composable
private fun QaAnswer(record: QaRecord, onSeekTo: ((Long) -> Unit)?, onDelete: () -> Unit) {
	Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(stringResource(StringsRes.string.feature_player_question, (record.question).toString()), style = MaterialTheme.typography.labelLarge)
		SelectionContainer { Text(record.answer, style = MaterialTheme.typography.bodyMedium) }
		record.coverageNote?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
		if (record.sourceUnverified) Text(stringResource(StringsRes.string.feature_player_source_unverified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
		if (record.citations.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			record.citations.forEach { c ->
				val start = c.startMs
				AssistChip(
					enabled = start != null && onSeekTo != null,
					onClick = { if (start != null) onSeekTo?.invoke(start) },
					label = { Text(start?.let { "S${c.segmentId} ${RecordingQa.clock(it)}" } ?: "S${c.segmentId}") },
				)
			}
		}
		TextButton(onClick = onDelete) { Text(stringResource(StringsRes.string.feature_player_delete_question_and_answer)) }
	}
}
