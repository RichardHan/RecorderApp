package com.eva.feature_player.clips

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.eva.recordings.domain.clips.ClipRangePolicy
import com.eva.recordings.domain.models.RecordingClipModel
import java.io.File

/** Draft of the range being edited in the dialog (plain so it can be tested without Compose). */
internal data class ClipDraft(
	val startText: String,
	val endText: String,
	val title: String,
	val maxMinutesText: String = (ClipRangePolicy.DEFAULT_MAX_CLIP_MS / 60_000).toString(),
) {
	val startMs: Long? get() = ClipRangePolicy.parseTime(startText)
	val endMs: Long? get() = ClipRangePolicy.parseTime(endText)
	val maxMs: Long
		get() = (maxMinutesText.trim().toLongOrNull() ?: (ClipRangePolicy.DEFAULT_MAX_CLIP_MS / 60_000))
			.coerceIn(1L, ClipRangePolicy.HARD_MAX_CLIP_MS / 60_000) * 60_000

	/** Null when savable, otherwise a user-facing reason. */
	fun problem(totalMs: Long): String? {
		val start = startMs ?: return UiStrings.get(StringsRes.string.feature_player_enter_start_time_as_min_sec, "開始時間格式應為 分:秒")
		val end = endMs ?: return UiStrings.get(StringsRes.string.feature_player_enter_end_time_as_min_sec, "結束時間格式應為 分:秒")
		return ClipRangePolicy.validate(start, end, totalMs, maxMs)?.let { ClipRangePolicy.problemMessage(it, maxMs) }
	}

	companion object {
		fun of(range: LongRange) = ClipDraft(
			startText = ClipRangePolicy.formatMs(range.first),
			endText = ClipRangePolicy.formatMs(range.last),
			title = ClipRangePolicy.defaultTitle(range.first, range.last),
		)
	}
}

/**
 * Clip list of one recording plus the "剪片段" dialog. Ranges come from the current play position, a bookmark, or
 * typed times; the source recording is never changed.
 */
@Composable
fun ClipsPanel(
	recordingId: Long,
	positionMs: () -> Long,
	totalMs: () -> Long,
	bookmarkTimesMs: List<Long>,
	onSeekTo: (Long) -> Unit,
	modifier: Modifier = Modifier,
	viewModel: ClipsViewModel = hiltViewModel(key = "clips-$recordingId"),
) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var previewing by remember { mutableStateOf(false) }
	val preview = remember { ClipPreviewPlayer(context.applicationContext, scope).also { it.onStateChanged = { p -> previewing = p } } }
	DisposableEffect(Unit) { onDispose { preview.stop() } }

	var draft by remember { mutableStateOf<ClipDraft?>(null) }

	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(StringsRes.string.feature_player_clip), style = MaterialTheme.typography.titleSmall)
			if (viewModel.clips.isEmpty()) {
				Text(stringResource(StringsRes.string.feature_player_no_clips_yet_clips_are_saved_separately), style = MaterialTheme.typography.bodySmall)
			} else {
				ClipsList(
					clips = viewModel.clips,
					onPreview = { clip ->
						if (previewing) preview.stop()
						else preview.play(File(clip.filePath).toUri(), 0L, clip.durationMs + 500L)
					},
					onSeek = { clip -> onSeekTo(clip.startMs) },
					onShare = viewModel::share,
					onRename = viewModel::rename,
					onDelete = viewModel::delete,
					modifier = Modifier.weight(1f, fill = false),
				)
			}
			Button(
				enabled = !viewModel.exporting && totalMs() > 0L,
				onClick = { draft = ClipDraft.of(ClipRangePolicy.fromPosition(positionMs(), totalMs())) },
				modifier = Modifier.fillMaxWidth(),
			) { Text(stringResource(StringsRes.string.feature_player_create_clip_at_current_position)) }
			if (viewModel.exporting) {
				LinearProgressIndicator(Modifier.fillMaxWidth())
				TextButton(onClick = viewModel::cancelExport) { Text(stringResource(StringsRes.string.feature_player_cancel_export)) }
			}
			viewModel.message?.let { msg ->
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
					Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
					TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(StringsRes.string.feature_player_got_it)) }
				}
			}
		}
	}

	draft?.let { current ->
		val total = totalMs()
		val problem = current.problem(total)
		AlertDialog(
			onDismissRequest = { preview.stop(); draft = null },
			title = { Text(stringResource(StringsRes.string.feature_player_create_clip)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						OutlinedTextField(
							value = current.startText,
							onValueChange = { draft = current.copy(startText = it) },
							label = { Text(stringResource(StringsRes.string.feature_player_start)) },
							singleLine = true,
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
							modifier = Modifier.weight(1f),
						)
						OutlinedTextField(
							value = current.endText,
							onValueChange = { draft = current.copy(endText = it) },
							label = { Text(stringResource(StringsRes.string.feature_player_end)) },
							singleLine = true,
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
							modifier = Modifier.weight(1f),
						)
					}
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						AssistChip(
							onClick = { draft = current.copy(startText = ClipRangePolicy.formatMs(positionMs())) },
							label = { Text(stringResource(StringsRes.string.feature_player_set_start_to_current_position)) },
						)
						AssistChip(
							onClick = { draft = current.copy(endText = ClipRangePolicy.formatMs(positionMs())) },
							label = { Text(stringResource(StringsRes.string.feature_player_set_end_to_current_position)) },
						)
					}
					if (bookmarkTimesMs.isNotEmpty()) {
						Text(stringResource(StringsRes.string.feature_player_from_bookmark_15_seconds_on_each_side), style = MaterialTheme.typography.labelMedium)
						FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							bookmarkTimesMs.sorted().take(12).forEach { point ->
								AssistChip(
									onClick = {
										val range = ClipRangePolicy.aroundBookmark(point, total)
										draft = ClipDraft.of(range).copy(maxMinutesText = current.maxMinutesText)
									},
									label = { Text(ClipRangePolicy.formatMs(point)) },
								)
							}
						}
					}
					OutlinedTextField(
						value = current.title,
						onValueChange = { draft = current.copy(title = it) },
						label = { Text(stringResource(StringsRes.string.feature_player_title)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
					)
					OutlinedTextField(
						value = current.maxMinutesText,
						onValueChange = { draft = current.copy(maxMinutesText = it.filter(Char::isDigit).take(2)) },
						label = { Text(stringResource(StringsRes.string.feature_player_maximum_length_in_minutes_up_to_10)) },
						singleLine = true,
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
						modifier = Modifier.fillMaxWidth(),
					)
					val start = current.startMs
					val end = current.endMs
					if (start != null && end != null && end > start) {
						Text(stringResource(StringsRes.string.feature_player_length, (ClipRangePolicy.formatMs(end - start)).toString()), style = MaterialTheme.typography.bodySmall)
					}
					problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
					TextButton(
						enabled = problem == null && viewModel.sourceUri != null,
						onClick = {
							val uri = viewModel.sourceUri
							if (previewing) preview.stop()
							else if (uri != null && start != null && end != null) preview.play(uri.toUri(), start, end)
						},
					) { Text(if (previewing) UiStrings.get(StringsRes.string.feature_player_stop_preview, "停止試聽") else UiStrings.get(StringsRes.string.feature_player_preview_clip, "試聽這一段")) }
					Text(
						stringResource(StringsRes.string.feature_player_saved_as_a_separate_file_sharing_sends),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			},
			confirmButton = {
				Row {
					TextButton(
						enabled = problem == null && !viewModel.exporting,
						onClick = {
							preview.stop()
							viewModel.export(current.startMs!!, current.endMs!!, total, current.title, current.maxMs, shareAfterSave = false)
							draft = null
						},
					) { Text(stringResource(StringsRes.string.feature_player_save)) }
					TextButton(
						enabled = problem == null && !viewModel.exporting,
						onClick = {
							preview.stop()
							viewModel.export(current.startMs!!, current.endMs!!, total, current.title, current.maxMs, shareAfterSave = true)
							draft = null
						},
					) { Text(stringResource(StringsRes.string.feature_player_save_and_share)) }
				}
			},
			dismissButton = { TextButton(onClick = { preview.stop(); draft = null }) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
		)
	}
}

@Composable
internal fun ClipsList(
	clips: List<RecordingClipModel>,
	onPreview: (RecordingClipModel) -> Unit,
	onSeek: (RecordingClipModel) -> Unit,
	onShare: (RecordingClipModel) -> Unit,
	onRename: (RecordingClipModel, String) -> Unit,
	onDelete: (RecordingClipModel) -> Unit,
	modifier: Modifier = Modifier,
) {
	var editingId by remember { mutableStateOf<Long?>(null) }
	var confirmDelete by remember { mutableStateOf<RecordingClipModel?>(null) }
	LazyColumn(modifier = modifier.fillMaxWidth().heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		items(clips, key = { it.id }) { clip ->
			Column(Modifier.fillMaxWidth()) {
				if (editingId == clip.id) {
					var text by remember(clip.id) { mutableStateOf(clip.title) }
					Row(verticalAlignment = Alignment.CenterVertically) {
						OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.weight(1f))
						TextButton(onClick = { onRename(clip, text); editingId = null }) { Text(stringResource(StringsRes.string.feature_player_save)) }
					}
				} else {
					Text(clip.title, style = MaterialTheme.typography.bodyMedium)
				}
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						"${ClipRangePolicy.formatMs(clip.startMs)}–${ClipRangePolicy.formatMs(clip.endMs)}（${ClipRangePolicy.formatMs(clip.durationMs)}）",
						style = MaterialTheme.typography.labelMedium,
						modifier = Modifier.weight(1f),
					)
					TextButton(onClick = { onPreview(clip) }) { Text(stringResource(StringsRes.string.feature_player_preview)) }
					TextButton(onClick = { onSeek(clip) }) { Text(stringResource(StringsRes.string.feature_player_go_to_source)) }
					TextButton(onClick = { editingId = clip.id }) { Text(stringResource(StringsRes.string.feature_player_rename)) }
					IconButton(onClick = { onShare(clip) }, modifier = Modifier.semantics { contentDescription = UiStrings.get(StringsRes.string.feature_player_share_clip_alt, "分享片段 %1\$s", clip.title) }) {
						Icon(Icons.Default.Share, contentDescription = null)
					}
					IconButton(onClick = { confirmDelete = clip }, modifier = Modifier.semantics { contentDescription = UiStrings.get(StringsRes.string.feature_player_delete_clip_alt, "刪除片段 %1\$s", clip.title) }) {
						Icon(Icons.Default.Delete, contentDescription = null)
					}
				}
			}
		}
	}
	confirmDelete?.let { clip ->
		AlertDialog(
			onDismissRequest = { confirmDelete = null },
			title = { Text(stringResource(StringsRes.string.feature_player_delete_clip)) },
			text = { Text(stringResource(StringsRes.string.feature_player_only_the_clip_file_will_be_deleted)) },
			confirmButton = { TextButton({ onDelete(clip); confirmDelete = null }) { Text(stringResource(StringsRes.string.feature_player_delete)) } },
			dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
		)
	}
}
