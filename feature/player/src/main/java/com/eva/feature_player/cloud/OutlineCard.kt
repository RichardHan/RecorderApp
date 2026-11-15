package com.eva.feature_player.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.eva.cloudtranscribe.outline.OutlineNode
import com.eva.cloudtranscribe.outline.SummaryOutline

/**
 * "大綱" card for the newest summary: collapsible local outline by default (no cost); optional, confirmed
 * "整理成大綱" sends only the summary text. Collapsed by default so the dialog stays short.
 */
@Composable
internal fun OutlineCard(
	recordingId: Long,
	summaryId: String?,
	summaryText: String?,
	viewModel: OutlineViewModel = hiltViewModel(key = "outline-$recordingId"),
) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	var open by rememberSaveable(recordingId) { mutableStateOf(false) }
	var mode by rememberSaveable(recordingId) { mutableStateOf(OutlineMode.LOCAL) }
	var confirm by remember { mutableStateOf(false) }
	val saved = viewModel.saved
	val empty = SummaryOutline.isEmptySummary(summaryText)
	LaunchedEffect(saved) { if (saved != null) mode = OutlineMode.AI else mode = OutlineMode.LOCAL }
	val nodes = remember(mode, saved, summaryText) { OutlineRules.display(mode, saved, summaryText) }
	val clipboard = LocalClipboardManager.current

	Card {
		Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }) {
				Text((if (open) "▾ " else "▸ ") + "大綱", style = MaterialTheme.typography.titleSmall)
			}
			if (!open) return@Column
			if (empty) {
				Text("還沒有可整理的摘要（或摘要為「目前無重點」）。", style = MaterialTheme.typography.bodySmall)
				return@Column
			}
			if (saved != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				FilterChip(mode == OutlineMode.LOCAL, { mode = OutlineMode.LOCAL }, { Text("本機大綱") })
				FilterChip(mode == OutlineMode.AI, { mode = OutlineMode.AI }, { Text("AI 大綱") })
			}
			if (mode == OutlineMode.AI && OutlineRules.isStale(saved, summaryId, summaryText)) {
				Text("摘要已更新，AI 大綱可能已過期（不會自動重算）", style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.secondary)
			}
			OutlineTree(nodes)
			Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				OutlinedButton(enabled = !viewModel.working, onClick = { confirm = true }) {
					Text(if (viewModel.working) "整理中…" else "整理成大綱（AI）")
				}
				if (viewModel.working) TextButton(onClick = viewModel::cancel) { Text("取消") }
				if (saved != null && !viewModel.working) TextButton(onClick = viewModel::restoreLocal) { Text("回復本機大綱") }
			}
			TextButton(onClick = { clipboard.setText(AnnotatedString(SummaryOutline.asText(nodes))) }) { Text("複製大綱") }
			viewModel.message?.let { msg ->
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
					TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
				}
			}
		}
	}

	if (confirm && summaryId != null && summaryText != null) AlertDialog(
		onDismissRequest = { confirm = false },
		title = { Text("用 AI 整理成大綱？") },
		text = {
			Text("只會把這份摘要文字（不是整份逐字稿、也不含音訊）送到目前設定的摘要服務，使用已儲存的 API Key，仍可能產生 token 費用。" +
				"結果最多兩層、20 個節點；格式不符、失敗或取消時保留本機大綱。")
		},
		confirmButton = { TextButton({ confirm = false; viewModel.organise(summaryId, summaryText) }) { Text("送出並整理") } },
		dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
	)
}

/** Two-level collapsible list; plain Column (it lives inside the dialog's LazyColumn). */
@Composable
internal fun OutlineTree(nodes: List<OutlineNode>) {
	var collapsed by remember(nodes) { mutableStateOf(emptySet<Int>()) }
	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		TextButton(onClick = { collapsed = emptySet() }) { Text("全部展開") }
		TextButton(onClick = { collapsed = nodes.indices.toSet() }) { Text("全部摺疊") }
	}
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		nodes.forEachIndexed { i, node ->
			val isCollapsed = i in collapsed
			Text(
				(if (node.children.isEmpty()) "• " else if (isCollapsed) "▸ " else "▾ ") + node.title,
				style = MaterialTheme.typography.bodyMedium,
				modifier = Modifier.fillMaxWidth().clickable(enabled = node.children.isNotEmpty(), role = Role.Button) {
					collapsed = if (isCollapsed) collapsed - i else collapsed + i
				}.padding(vertical = 4.dp),
			)
			if (!isCollapsed) node.children.forEach { child ->
				Text("– ${child.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 20.dp, bottom = 2.dp))
			}
		}
	}
}
