package com.eva.feature_player.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.player.domain.model.PlayerTrackData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only: lists transcripts already saved for a recording. Opening the player never starts a transcription. */
@HiltViewModel
class SavedTranscriptViewModel @Inject constructor(private val store: TranscriptStore) : ViewModel() {
	private val _records = MutableStateFlow<List<TranscriptRecord>?>(null)
	val records = _records.asStateFlow()
	private var loadedFor: Long? = null

	fun load(recordingId: Long, force: Boolean = false) {
		if (!force && loadedFor == recordingId) return
		loadedFor = recordingId
		viewModelScope.launch {
			_records.value = withContext(Dispatchers.IO) {
				runCatching { PlaybackTranscripts.selectable(store.transcripts(recordingId)) }.getOrDefault(emptyList())
			}
		}
	}
}

@Composable
internal fun SavedTranscriptPanel(
	recordingId: Long,
	trackData: () -> PlayerTrackData,
	modifier: Modifier = Modifier,
	refreshKey: Int = 0,
	onSeekTo: ((Long) -> Unit)? = null,
	viewModel: SavedTranscriptViewModel = hiltViewModel(key = "saved-transcripts-$recordingId"),
) {
	LaunchedEffect(recordingId, refreshKey) { viewModel.load(recordingId, force = refreshKey > 0) }
	val records by viewModel.records.collectAsStateWithLifecycle()
	var selectedId by rememberSaveable(recordingId) { mutableStateOf<String?>(null) }

	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.fillMaxSize().padding(10.dp)) {
			val list = records
			when {
				list == null -> Text("讀取逐字稿…", style = MaterialTheme.typography.labelMedium)
				list.isEmpty() -> Text(
					"這段錄音還沒有已儲存的逐字稿（可從上方「雲端轉錄」選單建立）",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				else -> {
					val selected = list.firstOrNull { it.id == selectedId } ?: PlaybackTranscripts.defaultSelection(list) ?: list.first()
					if (list.size > 1) Row(
						horizontalArrangement = Arrangement.spacedBy(6.dp),
						modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
					) {
						list.forEach { r ->
							FilterChip(
								selected = r.id == selected.id,
								onClick = { selectedId = r.id },
								label = { Text(PlaybackTranscripts.label(r)) },
							)
						}
					} else Text(PlaybackTranscripts.label(selected), style = MaterialTheme.typography.labelMedium)
					TranscriptLines(selected, trackData, onSeekTo, Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp))
				}
			}
		}
	}
}

@Composable
private fun TranscriptLines(
	record: TranscriptRecord,
	trackData: () -> PlayerTrackData,
	onSeekTo: ((Long) -> Unit)?,
	modifier: Modifier,
) {
	val lines = remember(record.id, record.updatedAtMillis) { PlaybackTranscripts.lines(record) }
	val timed = lines.any { it.startMs != null }
	val listState = rememberLazyListState()
	val active by remember(lines) {
		derivedStateOf { if (timed) PlaybackTranscripts.activeIndex(lines, trackData().current.inWholeMilliseconds) else -1 }
	}
	LaunchedEffect(active) { if (active >= 0) listState.animateScrollToItem(active) }

	Column(modifier) {
		if (!timed) Text(
			"此逐字稿沒有時間戳，顯示全文（不跟隨播放位置）",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		SelectionContainer(Modifier.weight(1f)) {
			LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
				itemsIndexed(lines) { i, line ->
					val isActive = i == active
					Text(
						text = (line.startMs?.let { fmt(it) + "  " } ?: "") + line.text,
						style = MaterialTheme.typography.bodyMedium,
						color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
						modifier = Modifier
							.fillMaxWidth()
							.then(if (isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small) else Modifier)
							.then(line.startMs?.let { start -> if (onSeekTo != null) Modifier.clickable { onSeekTo(start) } else null } ?: Modifier)
							.padding(4.dp),
					)
				}
			}
		}
	}
}

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
