package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * Local to-do card of one recording, collapsed by default. Items are only created after the user picks summary
 * bullets in an explicit dialog or types one; nothing is generated or uploaded.
 */
@Composable
fun ActionItemsCard(
	recordingId: Long,
	latestSummaryText: String?,
	latestSummaryAtMillis: Long?,
	modifier: Modifier = Modifier,
	viewModel: ActionItemsViewModel = hiltViewModel(key = "action-items-$recordingId"),
) {
	LaunchedEffect(recordingId) { viewModel.bind(recordingId) }
	val context = LocalContext.current
	var manualDialog by remember { mutableStateOf(false) }

	val doneCount = viewModel.items.count { it.isDone }
	val summaryIsNewer = latestSummaryText != null &&
		viewModel.items.isNotEmpty() &&
		(latestSummaryAtMillis ?: 0L) > (viewModel.listStartedAtMillis ?: Long.MAX_VALUE)

	Card(modifier.fillMaxWidth()) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Row(
				Modifier.fillMaxWidth().clickable { viewModel.onExpandedChange(!viewModel.expanded) },
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					if (viewModel.items.isEmpty()) UiStrings.get(StringsRes.string.feature_player_action_items_alt, "待辦") else UiStrings.get(StringsRes.string.feature_player_action_items_alt_alt, "待辦（%1\$s/%2\$s）", doneCount, viewModel.items.size),
					style = MaterialTheme.typography.titleSmall,
				)
				Icon(
					imageVector = if (viewModel.expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
					contentDescription = if (viewModel.expanded) UiStrings.get(StringsRes.string.feature_player_collapse_action_items, "收合待辦") else UiStrings.get(StringsRes.string.feature_player_expand_action_items, "展開待辦"),
				)
			}
			if (viewModel.expanded) {
				if (summaryIsNewer) {
					Text(
						stringResource(StringsRes.string.feature_player_highlights_changed_you_can_create_action_items),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.secondary,
					)
				}
				if (viewModel.items.isEmpty()) {
					Text(stringResource(StringsRes.string.feature_player_no_action_items_only_summary_items_you), style = MaterialTheme.typography.bodySmall)
				} else {
					val byId = viewModel.items.associateBy { it.id }
					ActionItemsListContent(
						items = viewModel.items.map { ActionItemUi(it.id, it.text, it.isDone) },
						editingId = viewModel.editingId,
						onToggle = { id, done -> byId[id]?.let { viewModel.setDone(it, done) } },
						onStartEdit = viewModel::startEditing,
						onSaveText = { id, text -> byId[id]?.let { viewModel.saveText(it, text) } },
						onDelete = { id -> byId[id]?.let(viewModel::delete) },
					)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
					OutlinedButton(
						enabled = latestSummaryText != null,
						onClick = { latestSummaryText?.let(viewModel::startPicking) },
					) { Text(stringResource(StringsRes.string.feature_player_create_action_items_from_highlights)) }
					OutlinedButton(onClick = { manualDialog = true }) { Text(stringResource(StringsRes.string.feature_player_add_action_item)) }
				}
				OutlinedButton(
					enabled = viewModel.items.any { !it.isDone },
					onClick = {
						val text = viewModel.unfinishedClipboardText()
						val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
						clipboard.setPrimaryClip(ClipData.newPlainText(UiStrings.get(StringsRes.string.feature_player_action_items_alt, "待辦"), text))
					},
				) { Text(stringResource(StringsRes.string.feature_player_copy_open_items)) }
			}
			viewModel.message?.let { msg ->
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
					Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
					TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(StringsRes.string.feature_player_got_it)) }
				}
			}
		}
	}

	if (viewModel.candidates.isNotEmpty()) {
		AlertDialog(
			onDismissRequest = viewModel::cancelPicking,
			title = { Text(stringResource(StringsRes.string.feature_player_create_action_items_from_highlights)) },
			text = {
				Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
					Text(stringResource(StringsRes.string.feature_player_only_checked_items_will_be_added_updating),
						style = MaterialTheme.typography.bodySmall)
					viewModel.candidates.forEach { candidate ->
						Row(
							Modifier.fillMaxWidth().clickable { viewModel.toggleCandidate(candidate) },
							verticalAlignment = Alignment.CenterVertically,
						) {
							Checkbox(checked = candidate in viewModel.selected, onCheckedChange = { viewModel.toggleCandidate(candidate) })
							Text(candidate, style = MaterialTheme.typography.bodyMedium)
						}
					}
				}
			},
			confirmButton = {
				TextButton(enabled = viewModel.selected.isNotEmpty(), onClick = { viewModel.confirmAddSelected() }) {
					Text(stringResource(StringsRes.string.feature_player_add_items, (viewModel.selected.size).toString()))
				}
			},
			dismissButton = { TextButton(onClick = viewModel::cancelPicking) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
		)
	}

	if (manualDialog) ManualItemDialog(
		onDismiss = { manualDialog = false },
		onConfirm = { text -> viewModel.addManual(text); manualDialog = false },
	)
}

/** Minimal row model so the list can be rendered and tested without the persistence model. */
internal data class ActionItemUi(val id: Long, val text: String, val isDone: Boolean)

/** Rows of the expanded card; kept stateless so the checkbox/edit/delete wiring can be tested on its own. */
@Composable
internal fun ActionItemsListContent(
	items: List<ActionItemUi>,
	editingId: Long?,
	onToggle: (Long, Boolean) -> Unit,
	onStartEdit: (Long) -> Unit,
	onSaveText: (Long, String) -> Unit,
	onDelete: (Long) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		items.forEach { item ->
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Checkbox(checked = item.isDone, onCheckedChange = { onToggle(item.id, it) })
				if (editingId == item.id) {
					var text by remember(item.id) { mutableStateOf(item.text) }
					OutlinedTextField(
						value = text,
						onValueChange = { text = it },
						singleLine = true,
						modifier = Modifier.weight(1f),
					)
					TextButton(onClick = { onSaveText(item.id, text) }) { Text(stringResource(StringsRes.string.feature_player_save)) }
				} else {
					Text(
						text = item.text,
						style = MaterialTheme.typography.bodyMedium,
						textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
						modifier = Modifier.weight(1f).clickable { onStartEdit(item.id) }.padding(vertical = 8.dp),
					)
				}
				IconButton(onClick = { onDelete(item.id) }) {
					Icon(Icons.Default.Delete, contentDescription = stringResource(StringsRes.string.feature_player_delete_action_item))
				}
			}
		}
	}
}

@Composable
private fun ManualItemDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
	var text by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(StringsRes.string.feature_player_add_action_item)) },
		text = {
			OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(StringsRes.string.feature_player_action_item)) }, modifier = Modifier.fillMaxWidth())
		},
		confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(stringResource(StringsRes.string.feature_player_add_alt)) } },
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
	)
}
