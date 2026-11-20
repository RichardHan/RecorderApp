package com.eva.feature_settings.composables.files

import com.eva.feature_settings.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

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
							transcript = runCatching { BulkTextExport.transcriptText(store.transcriptsWithEdits(r.id)) }.getOrNull(),
							summary = runCatching { BulkTextExport.summaryText(store.summaries(r.id)) }.getOrNull(),
						)
					}
					val out = context.contentResolver.openOutputStream(target, "wt") ?: error(UiStrings.get(StringsRes.string.feature_settings_could_not_open_file, "無法開啟檔案"))
					entries.size to BulkTextExport.write(entries, out)
				}
				message = UiStrings.get(StringsRes.string.feature_settings_exported_recordings_with_text, "已匯出 %1\$s 段錄音（%2\$s 段有文字資料）", total, folders)
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				message = UiStrings.get(StringsRes.string.feature_settings_export_failed_delete_the_incomplete_file_if, "匯出失敗：%1\$s（未完成的檔案請自行刪除）", e.message ?: e.javaClass.simpleName)
			} finally {
				busy = false
			}
		}
	}
}

internal fun defaultBulkFileName(today: LocalDate): String =
	UiStrings.get(StringsRes.string.feature_settings_recorderapp_text_export_zip, "RecorderApp-文字匯出-%1\$s.zip", today.format(DateTimeFormatter.BASIC_ISO_DATE))

@Composable
internal fun BulkTextExportItem(modifier: Modifier = Modifier, viewModel: BulkTextExportViewModel = hiltViewModel()) {
	val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
		uri?.let(viewModel::export)
	}
	Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		OutlinedButton(onClick = { launcher.launch(defaultBulkFileName(LocalDate.now())) }, enabled = !viewModel.busy) {
			Text(if (viewModel.busy) UiStrings.get(StringsRes.string.feature_settings_exporting, "匯出中…") else UiStrings.get(StringsRes.string.feature_settings_export_all_transcripts_and_summaries_zip, "匯出全部逐字稿與摘要（ZIP）"))
		}
		Text(
			stringResource(StringsRes.string.feature_settings_includes_transcripts_latest_summaries_and_an_index),
			style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		viewModel.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
	}
}
