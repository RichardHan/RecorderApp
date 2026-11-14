package com.eva.feature_player.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/**
 * "會後跟催草稿": generated only after the user confirms what is sent, edited freely, then copied or shared by the
 * user. There is no send button — nothing leaves the app until the user pastes or shares it.
 */
@Composable
fun FollowUpDraftCard(
	recordingId: Long,
	acceptedSummaryId: String?,
	acceptedSummaryText: String?,
	modifier: Modifier = Modifier,
	viewModel: FollowUpViewModel = hiltViewModel(key = "follow-up-$recordingId"),
) {
	LaunchedEffect(recordingId, acceptedSummaryId, acceptedSummaryText) {
		viewModel.bind(recordingId, acceptedSummaryId, acceptedSummaryText)
	}
	var confirm by remember { mutableStateOf(false) }
	var showStyle by remember { mutableStateOf(false) }
	var confirmDiscard by remember { mutableStateOf(false) }

	Card(modifier = modifier.fillMaxWidth()) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text("會後跟催草稿", style = MaterialTheme.typography.titleSmall)
			val materials = viewModel.materials
			if (materials == null) {
				Text("需要已完成的摘要或已加入的待辦，才能寫跟催草稿。", style = MaterialTheme.typography.bodySmall)
			}
			if (viewModel.possiblyStale) {
				Text("摘要或待辦已更新，這份草稿可能過期（不會自動重產生）。", style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.secondary)
			}
			if (viewModel.draftText.isNotEmpty() || viewModel.savedDraft != null) {
				OutlinedTextField(
					value = viewModel.draftText,
					onValueChange = viewModel::onDraftChange,
					label = { Text("草稿（可直接修改）") },
					modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
				)
				Text("請核對負責人、期限與事實後再傳出。App 不會自動寄出或貼到任何服務。",
					style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					Button(enabled = viewModel.draftText.isNotBlank(), onClick = viewModel::copy) { Text("複製") }
					OutlinedButton(enabled = viewModel.draftText.isNotBlank(), onClick = viewModel::share) { Text("用系統分享") }
					TextButton(enabled = viewModel.draftText.isNotBlank(), onClick = viewModel::saveLocally) { Text("存在本機") }
					TextButton(onClick = { confirmDiscard = true }) { Text("丟棄") }
				}
			}
			if (showStyle) {
				OutlinedTextField(
					value = viewModel.instructions,
					onValueChange = viewModel::onInstructionsChange,
					label = { Text("格式要求（空白＝內建預設）") },
					modifier = Modifier.fillMaxWidth(),
				)
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				OutlinedButton(
					enabled = materials != null && !viewModel.generating,
					onClick = { confirm = true },
				) { Text(if (viewModel.savedDraft != null) "重新產生草稿" else "產生跟催草稿") }
				TextButton(onClick = { showStyle = !showStyle }) { Text(if (showStyle) "隱藏格式要求" else "格式要求") }
			}
			if (viewModel.generating) {
				LinearProgressIndicator(Modifier.fillMaxWidth())
				TextButton(onClick = viewModel::cancel) { Text("取消產生") }
			}
			viewModel.message?.let { msg ->
				Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
				TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
			}
		}
	}

	if (confirm) {
		val materials = viewModel.materials
		AlertDialog(
			onDismissRequest = { confirm = false },
			title = { Text("產生跟催草稿？") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(
						"會把以下文字送到你在「設定 › 雲端」設定的摘要服務一次（可能產生 token 費用）：" +
							(if (materials?.summary != null) "已完成的摘要" else "") +
							(if (materials?.summary != null && materials.actionItems.isNotEmpty()) "、" else "") +
							(if (materials?.actionItems?.isNotEmpty() == true) "${materials.actionItems.size} 項待辦" else "") + "。"
					)
					Text("不會送出音訊或逐字稿全文，也不會自動寄信、貼到 Slack 或建立行程。")
					if (materials?.truncated == true) Text("資料較長，已截斷，沒有整份送出。", color = MaterialTheme.colorScheme.secondary)
					if (viewModel.savedDraft != null) Text("成功後會覆蓋目前存在本機的草稿；失敗或取消則保留。", color = MaterialTheme.colorScheme.secondary)
				}
			},
			confirmButton = { TextButton({ confirm = false; viewModel.generate() }) { Text("送出並產生") } },
			dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
		)
	}
	if (confirmDiscard) {
		AlertDialog(
			onDismissRequest = { confirmDiscard = false },
			title = { Text("丟棄草稿？") },
			text = { Text("會刪除這段錄音存在本機的跟催草稿。") },
			confirmButton = { TextButton({ confirmDiscard = false; viewModel.discard() }) { Text("丟棄") } },
			dismissButton = { TextButton({ confirmDiscard = false }) { Text("取消") } },
		)
	}
}
