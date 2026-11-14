package com.eva.feature_player.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/** A decision row before formatting; plain so the list can be rendered without Room. */
internal data class DecisionRowUi(val id: Long, val text: String, val startMs: Long?, val manual: Boolean)

/**
 * Decision list of one recording (separate from action items). Extraction only runs after the user confirms the
 * upload; rows with a real source time jump there, rows without one never show a time.
 */
@Composable
fun DecisionsPanel(
	recordingId: Long,
	onSeekTo: (Long) -> Unit,
	modifier: Modifier = Modifier,
	viewModel: DecisionsViewModel = hiltViewModel(key = "decisions-$recordingId"),
) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	var confirm by remember { mutableStateOf(false) }
	var newText by remember(recordingId) { mutableStateOf("") }

	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text("決議", style = MaterialTheme.typography.titleSmall)
			Text("已經做成的決定／共識，和待辦分開。", style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant)
			if (viewModel.possiblyStale) {
				Text("逐字稿已更新，抽出的決議可能已過期（不會自動重算）", style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.secondary)
			}
			if (viewModel.decisions.isEmpty()) {
				Text("尚無決議。可以手動新增，或按下方按鈕從逐字稿抽出。", style = MaterialTheme.typography.bodySmall)
			} else {
				DecisionsList(
					rows = viewModel.decisions.map { DecisionRowUi(it.id, it.text, it.startMs, !it.isExtracted) },
					onSeek = onSeekTo,
					onEdit = { id, text -> viewModel.decisions.firstOrNull { it.id == id }?.let { viewModel.edit(it, text) } },
					onDelete = { id -> viewModel.decisions.firstOrNull { it.id == id }?.let(viewModel::delete) },
					modifier = Modifier.weight(1f, fill = false),
				)
			}
			Row(verticalAlignment = Alignment.CenterVertically) {
				OutlinedTextField(
					value = newText,
					onValueChange = { newText = it.take(200) },
					label = { Text("手動新增決議") },
					singleLine = true,
					modifier = Modifier.weight(1f),
				)
				TextButton(enabled = newText.isNotBlank(), onClick = { viewModel.add(newText); newText = "" }) { Text("新增") }
			}
			Button(
				enabled = viewModel.canExtract && !viewModel.extracting,
				onClick = { confirm = true },
				modifier = Modifier.fillMaxWidth(),
			) { Text(if (viewModel.extracting) "抽出決議中…" else "抽出決議") }
			if (viewModel.extracting) TextButton(onClick = viewModel::cancelExtraction) { Text("取消") }
			viewModel.blockedReason?.let {
				Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			viewModel.message?.let { msg ->
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
					Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
					TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
				}
			}
		}
	}

	if (confirm) {
		AlertDialog(
			onDismissRequest = { confirm = false },
			title = { Text("抽出決議？") },
			text = {
				Text("會把一份帶句子編號的完整逐字稿送到目前設定的摘要服務（使用已儲存的 API Key），可能產生 token 費用。" +
					"模型只回傳決議文字與句子編號，時間由本機換算，不會捏造；不會推測負責人。" +
					"新的結果會取代上次抽出的決議，手動新增的會保留；失敗或取消不會儲存任何內容。")
			},
			confirmButton = { TextButton({ confirm = false; viewModel.extract() }) { Text("送出並抽出") } },
			dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
		)
	}
}

@Composable
internal fun DecisionsList(
	rows: List<DecisionRowUi>,
	onSeek: (Long) -> Unit,
	onEdit: (Long, String) -> Unit,
	onDelete: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	var editingId by remember { mutableStateOf<Long?>(null) }
	LazyColumn(modifier = modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		items(rows, key = { it.id }) { row ->
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				val time = row.startMs
				Text(
					time?.let(::formatChapterTime) ?: (if (row.manual) "手動" else "—"),
					style = MaterialTheme.typography.labelMedium,
					modifier = Modifier.padding(end = 8.dp),
				)
				if (editingId == row.id) {
					var text by remember(row.id) { mutableStateOf(row.text) }
					OutlinedTextField(value = text, onValueChange = { text = it.take(200) }, modifier = Modifier.weight(1f))
					TextButton(onClick = { onEdit(row.id, text); editingId = null }) { Text("儲存") }
				} else {
					Text(
						row.text,
						style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.weight(1f)
							.let { m -> if (time != null) m.clickable { onSeek(time) } else m }
							.padding(vertical = 8.dp),
					)
					IconButton(onClick = { editingId = row.id }) { Text("✎") }
				}
				IconButton(onClick = { onDelete(row.id) }) {
					Icon(Icons.Default.Delete, contentDescription = "刪除決議")
				}
			}
		}
	}
}
