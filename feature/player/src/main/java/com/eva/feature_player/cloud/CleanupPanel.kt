package com.eva.feature_player.cloud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.eva.cloudtranscribe.cleanup.CleanupIntensity
import com.eva.cloudtranscribe.cleanup.CleanupRecord
import com.eva.cloudtranscribe.cleanup.CleanupSentence
import com.eva.cloudtranscribe.cleanup.TranscriptCleanup
import com.eva.feature_player.R

/** One displayed row: cleaned text plus the ORIGINAL sentence (and its real time) it came from. */
internal data class CleanupRowUi(val id: Int, val cleaned: String, val original: String, val startMs: Long?)

/** Joins stored cleaned sentences with the current original sentences by id; blanks (merged/filler) are hidden. */
internal fun cleanupRows(record: CleanupRecord, originals: List<CleanupSentence>): List<CleanupRowUi> {
	val byId = originals.associateBy { it.id }
	return record.sentences.filter { it.text.isNotBlank() }.map { s ->
		val o = byId[s.id]
		CleanupRowUi(s.id, s.text, o?.text.orEmpty(), o?.startMs)
	}
}

@Composable
fun CleanupPanel(
	recordingId: Long,
	onSeekTo: (Long) -> Unit,
	modifier: Modifier = Modifier,
	viewModel: CleanupViewModel = hiltViewModel(key = "cleanup-$recordingId"),
) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	var confirm by remember { mutableStateOf(false) }
	var compare by rememberSaveable(recordingId) { mutableStateOf(false) }
	val context = LocalContext.current

	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(stringResource(R.string.cleanup_intro), style = MaterialTheme.typography.bodySmall)
			Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				CleanupIntensity.entries.forEach { level ->
					FilterChip(selected = viewModel.intensity == level, enabled = !viewModel.generating,
						onClick = { viewModel.selectIntensity(level) }, label = { Text(intensityLabel(level)) })
				}
			}
			val record = viewModel.cleanup
			if (viewModel.possiblyStale) Text(stringResource(R.string.cleanup_stale), style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.secondary)
			if (record == null) {
				Text(stringResource(R.string.cleanup_empty), style = MaterialTheme.typography.bodySmall)
			} else {
				Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					FilterChip(selected = !compare, onClick = { compare = false }, label = { Text(stringResource(R.string.cleanup_view_clean)) })
					FilterChip(selected = compare, onClick = { compare = true }, label = { Text(stringResource(R.string.cleanup_view_compare)) })
					TextButton({
						val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
						clipboard.setPrimaryClip(ClipData.newPlainText("清稿", TranscriptCleanup.plainText(record)))
						Toast.makeText(context, R.string.cleanup_copied, Toast.LENGTH_SHORT).show()
					}) { Text(stringResource(R.string.cleanup_copy)) }
					TextButton(onClick = viewModel::delete, enabled = !viewModel.generating) { Text(stringResource(R.string.cleanup_delete)) }
				}
				CleanupList(cleanupRows(record, viewModel.sentences), compare, onSeekTo, Modifier.weight(1f, fill = false))
			}
			Button(onClick = { confirm = true }, enabled = viewModel.blockedReason == null && !viewModel.generating,
				modifier = Modifier.fillMaxWidth().testTag("cleanup-generate")) {
				Text(when {
					viewModel.generating -> viewModel.progress?.let { (done, total) -> stringResource(R.string.cleanup_running, done + 1, total) } ?: stringResource(R.string.cleanup_running_plain)
					record != null -> stringResource(R.string.cleanup_regenerate)
					else -> stringResource(R.string.cleanup_generate)
				})
			}
			if (viewModel.generating) TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.cleanup_cancel)) }
			viewModel.blockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
			viewModel.message?.let { msg ->
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
					TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.cleanup_ok)) }
				}
			}
		}
	}

	if (confirm) AlertDialog(
		onDismissRequest = { confirm = false },
		title = { Text(stringResource(R.string.cleanup_confirm_title)) },
		text = { Text(stringResource(R.string.cleanup_confirm_body, viewModel.characterCount, viewModel.requestCount)) },
		confirmButton = { TextButton({ confirm = false; viewModel.generate() }) { Text(stringResource(R.string.cleanup_confirm_send)) } },
		dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cleanup_cancel)) } },
	)
}

@Composable
private fun intensityLabel(level: CleanupIntensity) = when (level) {
	CleanupIntensity.LOW -> stringResource(R.string.cleanup_intensity_low)
	CleanupIntensity.MEDIUM -> stringResource(R.string.cleanup_intensity_medium)
}

/** Stateless list so seeking and compare view can be tested without a ViewModel. Times are the original sentence's. */
@Composable
internal fun CleanupList(rows: List<CleanupRowUi>, compare: Boolean, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
	SelectionContainer(modifier) {
		LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			items(rows, key = { it.id }) { row ->
				Column(Modifier.fillMaxWidth().testTag("cleanup-row-${row.id}")
					.then(row.startMs?.let { start -> Modifier.clickable { onSeek(start) } } ?: Modifier)
					.padding(4.dp)) {
					Text((row.startMs?.let { formatChapterTime(it) + "  " } ?: "") + row.cleaned, style = MaterialTheme.typography.bodyMedium)
					if (compare) Text(stringResource(R.string.cleanup_original_prefix, row.original), style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}
