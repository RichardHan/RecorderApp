package com.eva.feature_player.cloud

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
			Text("問這段錄音", style = MaterialTheme.typography.titleSmall)
			OutlinedTextField(
				value = question,
				onValueChange = { question = it.take(RecordingQa.MAX_QUESTION_CHARS) },
				label = { Text("例如：報價什麼時候要交？") },
				enabled = viewModel.blockedReason == null && !viewModel.asking,
				modifier = Modifier.fillMaxWidth(),
			)
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				Button(
					enabled = viewModel.blockedReason == null && !viewModel.asking && question.isNotBlank(),
					onClick = { if (confirmed) viewModel.ask(question) else confirm = true },
				) { Text(if (viewModel.asking) "回答中…" else "送出") }
				if (viewModel.asking) TextButton(onClick = viewModel::cancel) { Text("取消") }
			}
			viewModel.blockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
			viewModel.message?.let { msg ->
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
					TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
				}
			}
			viewModel.history.forEach { record -> QaAnswer(record, onSeekTo, onDelete = { viewModel.delete(record) }) }
		}
	}

	if (confirm) AlertDialog(
		onDismissRequest = { confirm = false },
		title = { Text("送出問題？") },
		text = {
			Text("每問一題會呼叫一次目前設定的摘要服務（使用已儲存的 API Key），並送出這份逐字稿文字（${viewModel.scope ?: "逐字稿"}），可能產生 token 費用。" +
				"不會送音訊。回答只根據逐字稿，找不到會直接說找不到。")
		},
		confirmButton = { TextButton({ confirm = false; confirmed = true; viewModel.ask(question) }) { Text("送出") } },
		dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
	)
}

@Composable
private fun QaAnswer(record: QaRecord, onSeekTo: ((Long) -> Unit)?, onDelete: () -> Unit) {
	Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text("問：${record.question}", style = MaterialTheme.typography.labelLarge)
		SelectionContainer { Text(record.answer, style = MaterialTheme.typography.bodyMedium) }
		record.coverageNote?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
		if (record.sourceUnverified) Text("未能驗證來源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
		TextButton(onClick = onDelete) { Text("刪除這筆問答") }
	}
}
