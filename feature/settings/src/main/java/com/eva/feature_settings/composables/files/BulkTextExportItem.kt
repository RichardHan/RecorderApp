package com.eva.feature_settings.composables.files

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.BulkExportEntry
import com.eva.cloudtranscribe.model.BulkTextExport
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.provider.VoiceRecordingsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
internal class BulkTextExportViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val recordings: VoiceRecordingsProvider,
	private val store: TranscriptStore,
) : ViewModel() {
	var busy by mutableStateOf(false)
		private set
	var message by mutableStateOf<String?>(null)
		private set

	/** Only runs after the user picked a destination in the system file picker. */
	fun export(target: Uri) {
		if (busy) return
		busy = true
		message = null
		viewModelScope.launch {
			try {
				val (total, folders) = withContext(Dispatchers.IO) {
					val entries = recordings.getVoiceRecordings().map { r ->
						BulkExportEntry(
							recordingId = r.id,
							title = r.title.ifBlank { r.displayName },
							recordedAt = r.recordedAt.toString(),
							durationMs = r.duration.inWholeMilliseconds,
							transcript = runCatching { BulkTextExport.transcriptText(store.transcripts(r.id)) }.getOrNull(),
							summary = runCatching { BulkTextExport.summaryText(store.summaries(r.id)) }.getOrNull(),
						)
					}
					val out = context.contentResolver.openOutputStream(target, "wt") ?: error("無法開啟檔案")
					entries.size to BulkTextExport.write(entries, out)
				}
				message = "已匯出 $total 段錄音（$folders 段有文字資料）"
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				message = "匯出失敗：${e.message ?: e.javaClass.simpleName}（未完成的檔案請自行刪除）"
			} finally {
				busy = false
			}
		}
	}
}

internal fun defaultBulkFileName(today: LocalDate): String =
	"RecorderApp-文字匯出-${today.format(DateTimeFormatter.BASIC_ISO_DATE)}.zip"

@Composable
internal fun BulkTextExportItem(modifier: Modifier = Modifier, viewModel: BulkTextExportViewModel = hiltViewModel()) {
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
		uri?.let(viewModel::export)
	}
	Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		OutlinedButton(onClick = { launcher.launch(defaultBulkFileName(LocalDate.now())) }, enabled = !viewModel.busy) {
			Text(if (viewModel.busy) "匯出中…" else "匯出全部逐字稿與摘要（ZIP）")
		}
		Text(
			"只包含文字（逐字稿、最新摘要、索引），不含音檔、API Key 或設定；存到你選的位置，不會上傳。",
			style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		viewModel.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
	}
}
