package com.eva.feature_recordings.search.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/** Entry button + dialog. [candidates] are (recordingId, name) from the current search results. */
@Composable
internal fun AskAcrossButton(
	candidates: List<Pair<Long, String>>,
	onOpenRecording: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (candidates.isEmpty()) return
	val viewModel = hiltViewModel<AskAcrossViewModel>()
	var open by rememberSaveable { mutableStateOf(false) }
	OutlinedButton(onClick = { viewModel.prepare(candidates); open = true }, modifier = modifier) {
		Text("問 AI（用這些結果）")
	}
	if (open) AskAcrossDialog(viewModel, onDismiss = { viewModel.reset(); open = false }, onOpenRecording = {
		viewModel.reset(); open = false; onOpenRecording(it)
	})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskAcrossDialog(viewModel: AskAcrossViewModel, onDismiss: () -> Unit, onOpenRecording: (Long) -> Unit) {
	var question by rememberSaveable { mutableStateOf("") }
	val plan = viewModel.plan
	val answer = viewModel.answer
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("跨錄音問 AI") },
		text = {
			Column(
				Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				if (viewModel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
				viewModel.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
				if (plan != null) {
					Text("來源（${plan.sources.size} 段）：" + plan.sources.joinToString("、") { it.name }, style = MaterialTheme.typography.bodySmall)
					if (answer == null) {
						OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth(), label = { Text("問題") }, maxLines = 4, enabled = !viewModel.busy)
						Text(
							"送出後會把這 ${plan.sources.size} 段錄音的逐字稿文字（約 ${plan.chars} 字）送到 ${plan.host}（模型 ${plan.model}），" +
								"共 1 次請求，可能產生 API 費用。不上傳音訊、不自動重試，回答不會儲存。",
							style = MaterialTheme.typography.bodySmall,
						)
					} else {
						SelectionContainer { Text(answer) }
						val cited = AskAcross.cited(answer, plan.sources.size)
						if (cited.isNotEmpty()) {
							Text("引用的錄音", style = MaterialTheme.typography.titleSmall)
							FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
								cited.forEach { i ->
									val s = plan.sources[i]
									AssistChip(onClick = { onOpenRecording(s.recordingId) }, label = { Text("R${i + 1} ${s.name}") })
								}
							}
						}
						Text("回答由 AI 根據逐字稿產生，請點來源核對。", style = MaterialTheme.typography.bodySmall)
					}
				}
			}
		},
		confirmButton = {
			when {
				plan != null && answer == null -> TextButton(
					enabled = !viewModel.busy && question.isNotBlank(),
					onClick = { viewModel.ask(question) },
				) { Text("同意費用，送出提問") }
				answer != null -> TextButton(onClick = { viewModel.clearAnswer() }) { Text("再問一題") }
				else -> {}
			}
		},
		dismissButton = {
			TextButton(onClick = { if (viewModel.busy) viewModel.cancel() else onDismiss() }) {
				Text(if (viewModel.busy) "取消" else "關閉")
			}
		},
	)
}
