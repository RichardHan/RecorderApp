package com.eva.feature_settings.composables.transcribe

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eva.transcribe.domain.models.STTModel

/** Every model download starts here: shows the real download size and needs an explicit tap for mobile data. */
@Composable
internal fun ConfirmModelDownloadDialog(model: STTModel, onDismiss: () -> Unit, onConfirm: (allowMetered: Boolean) -> Unit) {
	val size = Formatter.formatShortFileSize(LocalContext.current, model.archiveSize)
	val large = model.archiveSize >= 1_500_000_000L
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("下載 ${model.displayName}？") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text("下載大小：$size。預設只在 Wi‑Fi（不計費網路）下載；使用行動數據可能產生費用。")
				if (large) Text("實驗性大型模型：需要 64 位元裝置、下載時至少保留約 6 GB 可用空間，載入時會檢查實際可用記憶體；記憶體不足時只會停用逐字稿，錄音照常保存。")
				Text("可隨時取消，已下載的檔案會保留，下次從中斷處續傳。")
			}
		},
		confirmButton = { TextButton(onClick = { onConfirm(false) }) { Text("僅 Wi‑Fi 下載") } },
		dismissButton = {
			Column {
				TextButton(onClick = { onConfirm(true) }) { Text("允許行動數據") }
				TextButton(onClick = onDismiss) { Text("取消") }
			}
		},
	)
}
