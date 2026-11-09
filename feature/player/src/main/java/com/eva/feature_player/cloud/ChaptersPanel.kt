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
import androidx.compose.material3.OutlinedButton
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
import com.eva.recordings.domain.models.RecordingChapterModel

/** A chapter row before formatting: kept plain so the list can be rendered and tested without Room. */
internal data class ChapterRowUi(val id: Long, val title: String, val startMs: Long)

internal fun formatChapterTime(startMs: Long): String {
	val seconds = startMs / 1000
	return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Chapter list of one recording. Generation only happens after the user confirms the upload in the dialog; the list
 * itself shows stored chapters and jumps to their real start time.
 */
@Composable
fun ChaptersPanel(
	recordingId: Long,
	onSeekTo: (Long) -> Unit,
	modifier: Modifier = Modifier,
	viewModel: ChaptersViewModel = hiltViewModel(key = "chapters-$recordingId"),
) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	var confirm by remember { mutableStateOf(false) }

	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text("章節", style = MaterialTheme.typography.titleSmall)
			if (viewModel.possiblyStale) {
				Text("逐字稿已更新，章節可能已過期（不會自動重算）", style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.secondary)
			}
			if (viewModel.chapters.isEmpty()) {
				Text("尚無章節。章節只在使用者手動產生時建立。", style = MaterialTheme.typography.bodySmall)
			} else {
				ChaptersList(
					rows = viewModel.chapters.map { ChapterRowUi(it.id, it.title, it.startMs) },
					onSeek = onSeekTo,
					onRename = { id, title -> viewModel.chapters.firstOrNull { it.id == id }?.let { viewModel.rename(it, title) } },
					onDelete = { id -> viewModel.chapters.firstOrNull { it.id == id }?.let(viewModel::delete) },
					modifier = Modifier.weight(1f, fill = false),
				)
			}
			Button(
				enabled = viewModel.canGenerate && !viewModel.generating,
				onClick = { confirm = true },
				modifier = Modifier.fillMaxWidth(),
			) { Text(if (viewModel.generating) "產生章節中…" else "產生章節") }
			if (viewModel.generating) TextButton(onClick = viewModel::cancelGeneration) { Text("取消產生") }
			viewModel.blockedReason?.let {
				Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			viewModel.message?.let { msg ->
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
					TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
				}
			}
		}
	}

	if (confirm) {
		AlertDialog(
			onDismissRequest = { confirm = false },
			title = { Text("產生章節？") },
			text = {
				Text("會把目前設定的摘要服務（含模型與已儲存的 API Key）送出一份帶句子編號的完整逐字稿，可能產生 token 費用。" +
					"模型只會回傳句子編號與標題，時間由本機換算，不會捏造；失敗或取消不會儲存任何章節。")
			},
			confirmButton = {
				TextButton({
					confirm = false
					viewModel.generate()
				}) { Text("送出並產生") }
			},
			dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
		)
	}
}

/** Rows of the chapter list; stateless so seek/rename/delete wiring can be tested without a ViewModel. */
@Composable
internal fun ChaptersList(
	rows: List<ChapterRowUi>,
	onSeek: (Long) -> Unit,
	onRename: (Long, String) -> Unit,
	onDelete: (Long) -> Unit,
	modifier: Modifier = Modifier,
) {
	var editingId by remember { mutableStateOf<Long?>(null) }
	LazyColumn(modifier = modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		items(rows, key = { it.id }) { row ->
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Text(formatChapterTime(row.startMs), style = MaterialTheme.typography.labelMedium,
					modifier = Modifier.padding(end = 8.dp))
				if (editingId == row.id) {
					var text by remember(row.id) { mutableStateOf(row.title) }
					OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
						modifier = Modifier.weight(1f))
					TextButton(onClick = { onRename(row.id, text); editingId = null }) { Text("儲存") }
				} else {
					Text(row.title, style = MaterialTheme.typography.bodyMedium,
						modifier = Modifier.weight(1f).clickable { onSeek(row.startMs) }.padding(vertical = 8.dp))
					IconButton(onClick = { editingId = row.id }) { Text("✎") }
				}
				IconButton(onClick = { onDelete(row.id) }) {
					Icon(Icons.Default.Delete, contentDescription = "刪除章節")
				}
			}
		}
	}
}
