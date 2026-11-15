package com.eva.feature_recordings.recap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.provider.RecordingActionItemsProvider
import com.eva.recordings.domain.provider.VoiceRecordingsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

@HiltViewModel
internal class RecentRecapViewModel @Inject constructor(
	private val recordings: VoiceRecordingsProvider,
	private val actionItems: RecordingActionItemsProvider,
	private val store: TranscriptStore,
) : ViewModel() {
	var period by mutableStateOf(RecapPeriod.WEEK)
		private set
	var recap by mutableStateOf<Recap?>(null)
		private set
	var error by mutableStateOf<String?>(null)
		private set
	private var job: Job? = null

	fun load(p: RecapPeriod = period) {
		period = p
		job?.cancel()
		job = viewModelScope.launch {
			recap = null
			error = null
			try {
				recap = withContext(Dispatchers.IO) { build(p) }
			} catch (e: kotlinx.coroutines.CancellationException) {
				throw e
			} catch (e: Exception) {
				error = "無法讀取錄音清單"
			}
		}
	}

	private suspend fun build(p: RecapPeriod): Recap {
		val zone = ZoneId.systemDefault()
		val tz = TimeZone.currentSystemDefault()
		val now = System.currentTimeMillis()
		val all = recordings.getVoiceRecordings().map {
			RecapRecording(it.id, it.title.ifBlank { it.displayName }, it.recordedAt.toInstant(tz).toEpochMilliseconds(), it.duration.inWholeMilliseconds)
		}
		val (picked, more) = RecentRecap.select(all, RecentRecap.periodStart(p, now, zone), now)
		val entries = picked.map { r ->
			runCatching {
				val summary = store.summaries(r.id).firstOrNull { it.status == RunStatus.SUCCESS && it.text.isNotBlank() }?.text
				val open = actionItems.actionItems(r.id).filter { !it.isDone }.sortedBy { it.createdAtMillis }.map { it.text }
				RecentRecap.entry(r, summary, open)
			}.getOrElse { RecapEntry(r, null, emptyList(), 0, failed = true) }
		}
		return Recap(entries, more)
	}
}

@Composable
internal fun RecentRecapDialog(onDismiss: () -> Unit, onOpenRecording: (Long) -> Unit, viewModel: RecentRecapViewModel = hiltViewModel()) {
	LaunchedEffect(Unit) { viewModel.load() }
	val context = LocalContext.current
	val zone = ZoneId.systemDefault()
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("近期回顧") },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					RecapPeriod.entries.forEach { p -> FilterChip(viewModel.period == p, { viewModel.load(p) }, { Text(p.label) }) }
				}
				Text("只整理手機上已有的摘要與待辦，不會送出任何資料。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				val recap = viewModel.recap
				when {
					viewModel.error != null -> Text(viewModel.error!!, color = MaterialTheme.colorScheme.error)
					recap == null -> CircularProgressIndicator()
					recap.entries.isEmpty() -> Text("這段期間沒有錄音")
					else -> LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						items(recap.entries, key = { it.recording.id }) { e -> RecapCard(e, zone) { onOpenRecording(e.recording.id) } }
						if (recap.moreRecordings > 0) item { Text("另有 ${recap.moreRecordings} 段錄音未列出", style = MaterialTheme.typography.labelSmall) }
					}
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = {
					val r = viewModel.recap ?: return@TextButton
					val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
					clipboard.setPrimaryClip(ClipData.newPlainText("近期回顧", RecentRecap.toMarkdown(r, viewModel.period, zone)))
					Toast.makeText(context, "已複製", Toast.LENGTH_SHORT).show()
				},
				enabled = viewModel.recap?.entries?.isNotEmpty() == true,
			) { Text("複製 Markdown") }
		},
		dismissButton = { TextButton(onDismiss) { Text("關閉") } },
	)
}

@Composable
private fun RecapCard(e: RecapEntry, zone: ZoneId, onClick: () -> Unit) {
	Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
		Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
			Text(e.recording.title, style = MaterialTheme.typography.titleSmall)
			Text("${RecentRecap.date(e.recording.recordedAtMillis, zone)}・${RecentRecap.duration(e.recording.durationMs)}", style = MaterialTheme.typography.labelSmall)
			when {
				e.failed -> Text("讀取失敗", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
				e.points == null -> Text("尚無摘要", style = MaterialTheme.typography.bodySmall)
				e.points.isEmpty() -> Text("沒有重點", style = MaterialTheme.typography.bodySmall)
				else -> e.points.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
			}
			if (e.openItems.isNotEmpty()) {
				Text("未完成待辦", style = MaterialTheme.typography.labelMedium)
				e.openItems.forEach { Text("☐ $it", style = MaterialTheme.typography.bodySmall) }
				if (e.moreOpenItems > 0) Text("另有 ${e.moreOpenItems} 條", style = MaterialTheme.typography.labelSmall)
			}
		}
	}
}
