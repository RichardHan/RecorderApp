package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings

import kotlinx.datetime.toJavaLocalDateTime
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.eva.feature_player.R
import androidx.compose.runtime.produceState
import com.eva.cloudtranscribe.model.TranscriptKeywords
import com.eva.cloudtranscribe.model.TranscriptSearch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.EditableLine
import com.eva.cloudtranscribe.model.TranscriptEditing
import com.eva.cloudtranscribe.model.TranscriptEdits
import com.eva.cloudtranscribe.model.TranscriptRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.player.domain.model.PlayerTrackData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One saved transcript plus the user's on-device corrections for it. */
data class SavedTranscriptEntry(val raw: TranscriptRecord, val edits: TranscriptEdits?) {
	/** What is shown, searched and exported: corrected text, original ids and times. */
	val shown: TranscriptRecord = TranscriptEditing.apply(raw, edits)
	val lines: List<EditableLine> = TranscriptEditing.displayLines(raw, edits)
	/** Corrections exist but the original changed after they were made; they are not applied until the user decides. */
	val conflict: Boolean = edits != null && edits.lines.isNotEmpty() && !TranscriptEditing.matches(raw, edits)
	val editedCount: Int = if (TranscriptEditing.matches(raw, edits)) edits?.lines?.size ?: 0 else 0
	val canUndo: Boolean = TranscriptEditing.matches(raw, edits) && edits?.previous != null
}

/**
 * Lists transcripts already saved for a recording. Opening the player never starts a transcription. The only writes
 * are explicit on-device corrections, stored as an overlay next to the transcript (never sent anywhere, never logged).
 */
@HiltViewModel
class SavedTranscriptViewModel @Inject constructor(private val store: TranscriptStore) : ViewModel() {
	private val _records = MutableStateFlow<List<SavedTranscriptEntry>?>(null)
	val records = _records.asStateFlow()
	private val _message = MutableStateFlow<String?>(null)
	val message = _message.asStateFlow()
	private val _summaries = MutableStateFlow<List<com.eva.cloudtranscribe.model.SummaryRecord>>(emptyList())
	/** Saved summaries (read-only), only used to optionally include them in an export. */
	val summaries = _summaries.asStateFlow()
	private var loadedFor: Long? = null
	private val writes = Mutex()

	fun load(recordingId: Long, force: Boolean = false) {
		if (!force && loadedFor == recordingId) return
		loadedFor = recordingId
		viewModelScope.launch {
			_records.value = read(recordingId)
			_summaries.value = withContext(Dispatchers.IO) {
				runCatching { store.summaries(recordingId) }.getOrDefault(emptyList())
			}
		}
	}

	private suspend fun read(recordingId: Long): List<SavedTranscriptEntry> = withContext(Dispatchers.IO) {
		runCatching {
			PlaybackTranscripts.selectable(store.transcripts(recordingId)).map { SavedTranscriptEntry(it, store.edits(recordingId, it.id)) }
		}.getOrDefault(emptyList())
	}

	fun saveLine(entry: SavedTranscriptEntry, key: String, text: String) = update(entry) { raw, edits, now ->
		if (!TranscriptEditing.canEdit(raw)) return@update Result.failure(IllegalStateException(UiStrings.get(StringsRes.string.feature_player_wait_for_transcription_to_finish_before_editing, "仍在轉錄，完成後才能修改")))
		val next = TranscriptEditing.withLine(raw, edits, key, text, now)
			?: return@update Result.failure(IllegalArgumentException(UiStrings.get(StringsRes.string.feature_player_no_changes_saved_the_text_was_unchanged, "沒有變更，或會把整份逐字稿清空，未儲存")))
		Result.success(next)
	}

	fun undo(entry: SavedTranscriptEntry) = update(entry) { _, edits, now ->
		TranscriptEditing.undo(edits, now)?.let { Result.success(it) } ?: Result.failure(IllegalStateException(UiStrings.get(StringsRes.string.feature_player_no_edit_to_undo, "沒有可還原的修改")))
	}

	/** The original changed after the user corrected it: keep the corrections (re-anchored) or drop them. */
	fun resolveConflict(entry: SavedTranscriptEntry, keep: Boolean) {
		if (!keep) {
			viewModelScope.launch {
				writes.withLock { withContext(Dispatchers.IO) { store.deleteEdits(entry.raw.recordingId, entry.raw.id) } }
				_message.value = UiStrings.get(StringsRes.string.feature_player_edits_discarded_showing_the_original_transcript, "已捨棄先前的修改，顯示原始逐字稿")
				_records.value = read(entry.raw.recordingId)
			}
			return
		}
		update(entry) { raw, edits, now -> edits?.let { Result.success(TranscriptEditing.rebase(raw, it, now)) } ?: Result.failure(IllegalStateException(UiStrings.get(StringsRes.string.feature_player_no_changes, "沒有修改"))) }
	}

	fun dismissMessage() { _message.value = null }

	private fun update(entry: SavedTranscriptEntry, change: (TranscriptRecord, TranscriptEdits?, Long) -> Result<TranscriptEdits>) {
		val recordingId = entry.raw.recordingId
		viewModelScope.launch {
			val outcome = writes.withLock {
				withContext(Dispatchers.IO) {
					runCatching {
						// re-read both files so a background re-save of the original is never silently overwritten
						val raw = store.transcript(recordingId, entry.raw.id) ?: error(UiStrings.get(StringsRes.string.feature_player_transcript_not_found, "找不到這份逐字稿"))
						if (raw.updatedAtMillis != entry.raw.updatedAtMillis) error(UiStrings.get(StringsRes.string.feature_player_source_updated_review_the_latest_version_before, "原文剛剛更新了，請重新查看後再修改"))
						val next = change(raw, store.edits(recordingId, raw.id), System.currentTimeMillis()).getOrThrow()
						store.saveEdits(next)
					}
				}
			}
			_message.value = outcome.fold({ UiStrings.get(StringsRes.string.feature_player_saved_on_device_original_transcript_kept, "已儲存在手機上（原始逐字稿保留）") }, { it.message ?: UiStrings.get(StringsRes.string.feature_player_could_not_save, "儲存失敗") })
			_records.value = read(recordingId)
		}
	}
}

/** Edit hooks for [TranscriptLines]; null keeps the panel read-only. */
internal class TranscriptLineEditor(
	val editMode: Boolean,
	val onEditLine: (EditableLine) -> Unit,
	val onReplaceInLine: (EditableLine, String) -> Unit,
)

@Composable
internal fun SavedTranscriptPanel(
	recordingId: Long,
	trackData: () -> PlayerTrackData,
	modifier: Modifier = Modifier,
	refreshKey: Int = 0,
	onSeekTo: ((Long) -> Unit)? = null,
	exportContext: () -> TranscriptExportContext = { TranscriptExportContext() },
	viewModel: SavedTranscriptViewModel = hiltViewModel(key = "saved-transcripts-$recordingId"),
) {
	val photoVm: com.eva.feature_player.photos.SavedPhotosViewModel = hiltViewModel()
	LaunchedEffect(recordingId) { photoVm.photos.prepareRecording(recordingId) }
	val photoFlow = remember(recordingId) { photoVm.photos.observe("recording:$recordingId") }
	val photos by photoFlow.collectAsStateWithLifecycle(emptyList())
	val photoItems = photos.map { com.eva.ui.photos.PhotoItem(it.id, it.startMs, it.path, it.caption) }
	LaunchedEffect(recordingId, refreshKey) { viewModel.load(recordingId, force = refreshKey > 0) }
	val records by viewModel.records.collectAsStateWithLifecycle()
	val message by viewModel.message.collectAsStateWithLifecycle()
	val summaries by viewModel.summaries.collectAsStateWithLifecycle()
	var selectedId by rememberSaveable(recordingId) { mutableStateOf<String?>(null) }
	var editMode by rememberSaveable(recordingId) { mutableStateOf(false) }
	var editingKey by rememberSaveable(recordingId) { mutableStateOf<String?>(null) }
	var replacing by remember(recordingId) { mutableStateOf<Pair<EditableLine, String>?>(null) }

	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.fillMaxSize().padding(10.dp)) {
			com.eva.ui.photos.PhotoAttachments("recording:$recordingId", { trackData().current.inWholeMilliseconds }, photoItems,
				photoVm.photos::add, photoVm.photos::caption, photoVm.photos::delete, onSeekTo)
			val list = records
			when {
				list == null -> Text(stringResource(StringsRes.string.feature_player_loading_transcript), style = MaterialTheme.typography.labelMedium)
				list.isEmpty() -> Text(
					stringResource(StringsRes.string.feature_player_no_saved_transcript_open_cloud_transcription_above),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				else -> {
					val raws = list.map { it.raw }
					val selectedRaw = raws.firstOrNull { it.id == selectedId } ?: PlaybackTranscripts.defaultSelection(raws) ?: raws.first()
					val entry = list.first { it.raw.id == selectedRaw.id }
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(0.dp),
						modifier = Modifier.fillMaxWidth(),
					) {
						SourcePicker(raws, selectedRaw, modifier = Modifier.weight(1f)) { selectedId = it; editMode = false }
						TranscriptExportButton(entry.shown, extras = { exportContext().toExtras(entry.shown, summaries) }, compact = true)
						TranscriptEditToggle(entry, editMode, onToggle = { editMode = !editMode }, onUndo = { viewModel.undo(entry) })
					}
					TranscriptEditStatus(entry, editMode, onResolve = { keep -> viewModel.resolveConflict(entry, keep) })
					message?.let { m ->
						Row {
							Text(m, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
							TextButton(viewModel::dismissMessage) { Text(stringResource(R.string.transcript_edit_dismiss)) }
						}
					}
					val editor = if (TranscriptEditing.canEdit(entry.raw) && !entry.conflict) TranscriptLineEditor(
						editMode = editMode,
						onEditLine = { editingKey = it.key },
						onReplaceInLine = { line, query -> replacing = line to query },
					) else null
					val translationVm: TranslationViewModel = hiltViewModel(key = "translations-$recordingId")
					var lang by rememberSaveable(selectedRaw.id) { mutableStateOf<String?>(null) }
					var view by rememberSaveable(selectedRaw.id) { mutableStateOf(com.eva.cloudtranscribe.translate.TranslationView.ORIGINAL) }
					TranslationBar(entry.shown, translationVm, lang, { lang = it }, view, { view = it })
					val translation = TranslationRules.forRecord(translationVm.translations, entry.shown, lang)
					TranscriptLines(entry.shown, trackData, onSeekTo, Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp),
						keyedLines = entry.lines, editor = editor,
						photos = photoItems,
						translation = translation,
						view = if (translation == null) com.eva.cloudtranscribe.translate.TranslationView.ORIGINAL else view,
					)
					val editing = editingKey?.let { key -> TranscriptEditing.editableLines(entry.raw).firstOrNull { it.key == key } }
					if (editing != null) {
						val shownText = entry.lines.firstOrNull { it.key == editing.key }?.line?.text ?: ""
						EditLineDialog(editing, shownText, onDismiss = { editingKey = null }, onSave = { text ->
							editingKey = null
							viewModel.saveLine(entry, editing.key, text)
						})
					}
					replacing?.let { (line, query) ->
						ReplaceInLineDialog(line, query, onDismiss = { replacing = null }, onReplace = { replacement ->
							replacing = null
							viewModel.saveLine(entry, line.key, TranscriptEditing.replaceInLine(line.line.text, query, replacement))
						})
					}
				}
			}
		}
	}
}

@Composable
private fun TranscriptEditToggle(
	entry: SavedTranscriptEntry,
	editMode: Boolean,
	onToggle: () -> Unit,
	onUndo: () -> Unit,
) {
	if (!TranscriptEditing.canEdit(entry.raw) || entry.conflict) return
	IconButton(onClick = onToggle, modifier = Modifier.testTag("transcript-edit-toggle")) {
		Icon(
			painter = painterResource(id = com.eva.ui.R.drawable.ic_edit),
			contentDescription = stringResource(R.string.transcript_edit_toggle),
			tint = if (editMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
	if (entry.canUndo) {
		TextButton(onUndo) { Text(stringResource(R.string.transcript_edit_undo)) }
	}
}

@Composable
private fun TranscriptEditStatus(
	entry: SavedTranscriptEntry,
	editMode: Boolean,
	onResolve: (Boolean) -> Unit,
) {
	when {
		!TranscriptEditing.canEdit(entry.raw) -> Text(stringResource(R.string.transcript_edit_running), style = MaterialTheme.typography.labelSmall)
		entry.conflict -> Column {
			Text(stringResource(R.string.transcript_edit_conflict, entry.edits?.lines?.size ?: 0), style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.error)
			Row {
				TextButton({ onResolve(true) }) { Text(stringResource(R.string.transcript_edit_conflict_keep)) }
				TextButton({ onResolve(false) }) { Text(stringResource(R.string.transcript_edit_conflict_discard)) }
			}
		}
		entry.editedCount > 0 -> Text(
			stringResource(R.string.transcript_edit_count, entry.editedCount),
			style = MaterialTheme.typography.labelSmall,
		)
	}
	if (editMode && !entry.conflict && TranscriptEditing.canEdit(entry.raw)) Text(stringResource(R.string.transcript_edit_hint),
		style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EditLineDialog(line: EditableLine, shownText: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
	var text by rememberSaveable(line.key) { mutableStateOf(shownText) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.transcript_edit_title)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				line.line.startMs?.let { Text(stringResource(R.string.transcript_edit_time_kept, fmt(it)), style = MaterialTheme.typography.labelSmall) }
				OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().testTag("transcript-edit-input"))
				if (text.trim() != line.line.text) Text(stringResource(R.string.transcript_edit_original, line.line.text),
					style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				if (text.trim() != line.line.text) TextButton({ text = line.line.text }) { Text(stringResource(R.string.transcript_edit_restore_original)) }
			}
		},
		confirmButton = { TextButton({ onSave(text) }) { Text(stringResource(R.string.transcript_edit_save)) } },
		dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.transcript_export_cancel)) } },
	)
}

@Composable
private fun ReplaceInLineDialog(line: EditableLine, query: String, onDismiss: () -> Unit, onReplace: (String) -> Unit) {
	var replacement by rememberSaveable(line.key, query) { mutableStateOf("") }
	val preview = TranscriptEditing.replaceInLine(line.line.text, query, replacement)
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.transcript_replace_title, query.trim())) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
				OutlinedTextField(replacement, { replacement = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("transcript-replace-input"),
					label = { Text(stringResource(R.string.transcript_replace_label)) })
				Text(stringResource(R.string.transcript_replace_before, line.line.text), style = MaterialTheme.typography.labelSmall)
				Text(stringResource(R.string.transcript_replace_after, preview), style = MaterialTheme.typography.labelSmall)
			}
		},
		confirmButton = { TextButton({ onReplace(replacement) }, enabled = preview != line.line.text) { Text(stringResource(R.string.transcript_replace_confirm)) } },
		dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.transcript_replace_skip)) } },
	)
}

@Composable
internal fun TranscriptLines(
	record: TranscriptRecord,
	trackData: () -> PlayerTrackData,
	onSeekTo: ((Long) -> Unit)?,
	modifier: Modifier,
	keyedLines: List<EditableLine>? = null,
	photos: List<com.eva.ui.photos.PhotoItem> = emptyList(),
	editor: TranscriptLineEditor? = null,
	translation: com.eva.cloudtranscribe.translate.SavedTranslation? = null,
	view: com.eva.cloudtranscribe.translate.TranslationView = com.eva.cloudtranscribe.translate.TranslationView.ORIGINAL,
) {
	val keyed = remember(record.id, record.updatedAtMillis, keyedLines) { keyedLines ?: TranscriptEditing.displayLines(record, null) }
	val lines = remember(keyed) { keyed.map { it.line } }
	val editMode = editor?.editMode == true
	val translated = remember(lines, translation) { com.eva.cloudtranscribe.translate.TranscriptTranslation.aligned(lines.size, translation) }
	var query by rememberSaveable(record.id) { mutableStateOf("") }
	val searching = query.isNotBlank()
	val matches = remember(lines, query) { TranscriptSearch.matchingLines(lines, query) }
	var matchIndex by remember(record.id, query, lines) { mutableStateOf(if (matches.isEmpty()) -1 else 0) }
	var navigationRequest by remember(record.id) { mutableStateOf(0) }
	val timed = lines.any { it.startMs != null }
	// Photo -> line mapping is computed once per photos/lines change; doing it per rendered row was
	// O(visible rows × photos × lines).
	val photoLineStarts = remember(lines) { lines.map { it.startMs } }
	val photosByLine = remember(photos, photoLineStarts) {
		photos.mapNotNull { p -> com.eva.recordings.domain.models.PhotoRules.nearestLine(p.atMs, photoLineStarts)?.let { it to p } }
			.groupBy({ it.first }, { it.second })
	}
	val listState = rememberLazyListState()
	val active by remember(lines) {
		derivedStateOf { if (timed) PlaybackTranscripts.activeIndex(lines, trackData().current.inWholeMilliseconds) else -1 }
	}
	// follow playback only while the previous highlighted line is on screen (manual reading is not yanked back)
	var lastActive by remember(lines) { mutableStateOf(-1) }
	LaunchedEffect(active, searching, editMode) {
		if (searching || editMode) { lastActive = -1; return@LaunchedEffect }
		if (active < 0) return@LaunchedEffect
		val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
		if (lastActive < 0 || lastActive in visible) listState.animateScrollToItem(active)
		lastActive = active
	}

	LaunchedEffect(record.id, query, matches, matchIndex, navigationRequest) {
		matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(it) }
	}

	val keywords by produceState(emptyList<String>(), lines) {
		value = withContext(Dispatchers.Default) { TranscriptKeywords.extract(lines.map { it.text }) }
	}

	Column(modifier) {
		TranscriptKeywordChips(keywords, query, onPick = { query = it })
		OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("transcript-search-input"),
			label = { Text(stringResource(R.string.transcript_search_label)) })
		if (searching) {
			Text(if (matches.isEmpty()) stringResource(R.string.transcript_search_empty) else stringResource(R.string.transcript_search_count, matchIndex + 1, matches.size), style = MaterialTheme.typography.labelSmall)
			Row(Modifier.horizontalScroll(rememberScrollState())) {
				TextButton({ matchIndex = TranscriptSearch.next(matchIndex, matches.size, -1); navigationRequest++ }, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.transcript_search_previous)) }
				TextButton({ matchIndex = TranscriptSearch.next(matchIndex, matches.size, 1); navigationRequest++ }, enabled = matches.isNotEmpty()) { Text(stringResource(R.string.transcript_search_next)) }
				if (editMode) TextButton(
					{ matches.getOrNull(matchIndex)?.let { editor?.onReplaceInLine?.invoke(keyed[it], query) } },
					enabled = matchIndex in matches.indices,
					modifier = Modifier.testTag("transcript-replace-current"),
				) { Text(stringResource(R.string.transcript_replace_current)) }
				TextButton({ query = "" }) { Text(stringResource(R.string.transcript_search_clear)) }
			}
			Text(stringResource(R.string.transcript_search_hint), style = MaterialTheme.typography.labelSmall)
		}
		if (!timed) Text(
			stringResource(StringsRes.string.feature_player_this_transcript_has_no_timestamps_the_full),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		SelectionContainer(Modifier.weight(1f)) {
			LazyColumn(state = listState, modifier = Modifier.testTag("transcript-search-lines"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				itemsIndexed(lines) { i, line ->
					val linePhotos = photosByLine[i].orEmpty()
					val isActive = i == active
					val highlight = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer)
					val click: Modifier = when {
						editMode -> Modifier.clickable { editor?.onEditLine?.invoke(keyed[i]) }
						line.startMs != null && onSeekTo != null -> Modifier.clickable { onSeekTo(line.startMs!!) }
						else -> Modifier
					}
					val (shown, secondary) = com.eva.cloudtranscribe.translate.TranscriptTranslation.display(line.text, translated?.getOrNull(i), view)
					Text(
						text = buildAnnotatedString {
							val prefix = (if (editMode) "✎ " else "") + (line.startMs?.let { fmt(it) + "  " } ?: "")
							append(prefix); append(shown)
							// search always matches the original text, so highlight only when the original is shown
							if (shown == line.text) TranscriptSearch.ranges(line.text, query).forEach { range ->
								addStyle(highlight, prefix.length + range.first, prefix.length + range.last + 1)
							}
							if (secondary != null) {
								append("\n")
								val start = length
								append(secondary)
								addStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary), start, length)
							}
						},
						style = MaterialTheme.typography.bodyMedium,
						color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.testTag("transcript-line-$i")
							.fillMaxWidth()
							.then(if (isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small) else Modifier)
							.then(click)
							.padding(4.dp),
					)
					if (linePhotos.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
						linePhotos.forEach { com.eva.ui.photos.PhotoThumbnail(it, onSeekTo) }
					}
				}
			}
		}
	}
}

private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }


/** Compact chip/dropdown: current source on one ellipsized line; menu lists every variant with its full model name. */
@Composable
private fun SourcePicker(
	list: List<TranscriptRecord>,
	selected: TranscriptRecord,
	modifier: Modifier = Modifier,
	onSelect: (String) -> Unit,
) {
	var open by remember { mutableStateOf(false) }
	val label = (if (list.size > 1) UiStrings.get(StringsRes.string.feature_player_transcripts, "逐字稿（%1\$s 份）：", list.size) else UiStrings.get(StringsRes.string.feature_player_transcript_alt, "逐字稿：")) +
		PlaybackTranscripts.label(selected)
	Box(modifier) {
		AssistChip(
			onClick = { if (list.size > 1) open = true },
			enabled = true,
			label = {
				Text(
					label,
					style = MaterialTheme.typography.labelMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			},
			trailingIcon = if (list.size > 1) {
				{ Text("▾", style = MaterialTheme.typography.labelMedium) }
			} else null,
		)
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

/** What the player screen knows about the recording, for export headers. */
data class TranscriptExportContext(
	val title: String? = null,
	val recordedAt: kotlinx.datetime.LocalDateTime? = null,
	val bookmarks: List<com.eva.cloudtranscribe.model.ExportBookmark> = emptyList(),
)

internal fun TranscriptExportContext.toExtras(
	record: TranscriptRecord,
	summaries: List<com.eva.cloudtranscribe.model.SummaryRecord>,
): com.eva.cloudtranscribe.model.TranscriptExportExtras {
	val usable = summaries.filter { it.status == com.eva.cloudtranscribe.model.RunStatus.SUCCESS && it.text.isNotBlank() }
	val summary = usable.firstOrNull { it.sourceTranscriptId == record.id } ?: usable.firstOrNull()
	return com.eva.cloudtranscribe.model.TranscriptExportExtras(
		title = title,
		recordedAt = recordedAt?.toJavaLocalDateTime(),
		summary = summary?.text,
		summaryModel = summary?.provenance?.model,
		bookmarks = bookmarks,
	)
}
