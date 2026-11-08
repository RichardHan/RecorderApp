package com.eva.feature_player.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.eva.feature_player.R
import com.eva.cloudtranscribe.model.TranscriptSearch
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
import androidx.compose.ui.platform.testTag
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
					SourcePicker(list, selected) { selectedId = it }
					TranscriptLines(selected, trackData, onSeekTo, Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp))
				}
			}
		}
	}
}

@Composable
internal fun TranscriptLines(
	record: TranscriptRecord,
	trackData: () -> PlayerTrackData,
	onSeekTo: ((Long) -> Unit)?,
	modifier: Modifier,
) {
	val lines = remember(record.id, record.updatedAtMillis) { PlaybackTranscripts.lines(record) }
	var query by rememberSaveable(record.id) { mutableStateOf("") }
	val searching = query.isNotBlank()
	val matches = remember(lines, query) { TranscriptSearch.matchingLines(lines, query) }
	var matchIndex by remember(record.id, query, lines) { mutableStateOf(if (matches.isEmpty()) -1 else 0) }
	var navigationRequest by remember(record.id) { mutableStateOf(0) }
	val timed = lines.any { it.startMs != null }
	val listState = rememberLazyListState()
	val active by remember(lines) {
		derivedStateOf { if (timed) PlaybackTranscripts.activeIndex(lines, trackData().current.inWholeMilliseconds) else -1 }
	}
	// follow playback only while the previous highlighted line is on screen (manual reading is not yanked back)
	var lastActive by remember(lines) { mutableStateOf(-1) }
	LaunchedEffect(active, searching) {
		if (searching) { lastActive = -1; return@LaunchedEffect }
		if (active < 0) return@LaunchedEffect
		val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
		if (lastActive < 0 || lastActive in visible) listState.animateScrollToItem(active)
		lastActive = active
	}

	LaunchedEffect(record.id, query, matches, matchIndex, navigationRequest) {
		matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(it) }
	}

	Column(modifier) {
		OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("transcript-search-input"),
			label = { Text(stringResource(R.string.transcript_search_label)) })
		if (searching) {
			Text(if (matches.isEmpty()) stringResource(R.string.transcript_search_empty) else stringResource(R.string.transcript_search_count, matchIndex + 1, matches.size), style = MaterialTheme.typography.labelSmall)
			Row {
				TextButton({ matchIndex = TranscriptSearch.next(matchIndex, matches.size, -1); navigationRequest++ }, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.transcript_search_previous)) }
				TextButton({ matchIndex = TranscriptSearch.next(matchIndex, matches.size, 1); navigationRequest++ }, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.transcript_search_next)) }
				TextButton({ query = "" }) { Text(stringResource(R.string.transcript_search_clear)) }
			}
			Text(stringResource(R.string.transcript_search_hint), style = MaterialTheme.typography.labelSmall)
		}
		if (!timed) Text(
			"此逐字稿沒有時間戳，顯示全文（不跟隨播放位置）",
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		SelectionContainer(Modifier.weight(1f)) {
			LazyColumn(state = listState, modifier = Modifier.testTag("transcript-search-lines"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				itemsIndexed(lines) { i, line ->
					val isActive = i == active
					val highlight = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer)
					Text(
						text = buildAnnotatedString {
							val prefix = line.startMs?.let { fmt(it) + "  " } ?: ""
							append(prefix); append(line.text)
							TranscriptSearch.ranges(line.text, query).forEach { range ->
								addStyle(highlight, prefix.length + range.first, prefix.length + range.last + 1)
							}
						},
						style = MaterialTheme.typography.bodyMedium,
						color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.testTag("transcript-line-$i")
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


/** Full-width picker: current source on one wrapping line; menu lists every variant with its full model name. */
@Composable
private fun SourcePicker(list: List<TranscriptRecord>, selected: TranscriptRecord, onSelect: (String) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box(Modifier.fillMaxWidth()) {
		androidx.compose.material3.OutlinedButton(
			onClick = { if (list.size > 1) open = true },
			enabled = true,
			modifier = Modifier.fillMaxWidth(),
		) {
			Text(
				(if (list.size > 1) "逐字稿（${list.size} 份）：" else "逐字稿：") + PlaybackTranscripts.label(selected) + if (list.size > 1) "  ▾" else "",
				style = MaterialTheme.typography.labelLarge,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			list.forEach { r ->
				androidx.compose.material3.DropdownMenuItem(
					text = { Text((if (r.id == selected.id) "✓ " else "") + PlaybackTranscripts.label(r)) },
					onClick = { onSelect(r.id); open = false },
				)
			}
		}
	}
}
