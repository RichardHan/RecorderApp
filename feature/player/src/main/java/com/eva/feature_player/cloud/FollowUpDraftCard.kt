package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

/**
 * "會後跟催草稿": generated only after the user confirms what is sent, edited freely, then copied or shared by the
 * user. There is no send button — nothing leaves the app until the user pastes or shares it.
 */
@Composable
fun FollowUpDraftCard(
	recordingId: Long,
	acceptedSummaryId: String?,
	acceptedSummaryText: String?,
	modifier: Modifier = Modifier,
	viewModel: FollowUpViewModel = hiltViewModel(key = "follow-up-$recordingId"),
) {
	LaunchedEffect(recordingId, acceptedSummaryId, acceptedSummaryText) {
		viewModel.bind(recordingId, acceptedSummaryId, acceptedSummaryText)
	}
	var confirm by remember { mutableStateOf(false) }
	var showStyle by remember { mutableStateOf(false) }
	var confirmDiscard by remember { mutableStateOf(false) }

	Card(modifier = modifier.fillMaxWidth()) {
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(stringResource(StringsRes.string.feature_player_meeting_follow_up_draft), style = MaterialTheme.typography.titleSmall)
			val materials = viewModel.materials
			if (materials == null) {
				Text(stringResource(StringsRes.string.feature_player_complete_a_summary_or_add_action_items), style = MaterialTheme.typography.bodySmall)
			}
			if (viewModel.possiblyStale) {
				Text(stringResource(StringsRes.string.feature_player_summary_or_action_items_changed_generate_the), style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.secondary)
			}
			if (viewModel.draftText.isNotEmpty() || viewModel.savedDraft != null) {
				OutlinedTextField(
					value = viewModel.draftText,
					onValueChange = viewModel::onDraftChange,
					label = { Text(stringResource(StringsRes.string.feature_player_draft_editable)) },
					modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
				)
				Text(stringResource(StringsRes.string.feature_player_verify_owners_deadlines_and_facts_before_sharing),
					style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					Button(enabled = viewModel.draftText.isNotBlank(), onClick = viewModel::copy) { Text(stringResource(StringsRes.string.feature_player_copy)) }
					OutlinedButton(enabled = viewModel.draftText.isNotBlank(), onClick = viewModel::share) { Text(stringResource(StringsRes.string.feature_player_share)) }
					TextButton(enabled = viewModel.draftText.isNotBlank(), onClick = viewModel::saveLocally) { Text(stringResource(StringsRes.string.feature_player_save_on_device)) }
					TextButton(onClick = { confirmDiscard = true }) { Text(stringResource(StringsRes.string.feature_player_discard)) }
				}
			}
			if (showStyle) {
				OutlinedTextField(
					value = viewModel.instructions,
					onValueChange = viewModel::onInstructionsChange,
					label = { Text(stringResource(StringsRes.string.feature_player_format_instructions_leave_blank_for_default)) },
					modifier = Modifier.fillMaxWidth(),
				)
			}
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
				OutlinedButton(
					enabled = materials != null && !viewModel.generating,
					onClick = { confirm = true },
				) { Text(if (viewModel.savedDraft != null) UiStrings.get(StringsRes.string.feature_player_regenerate_draft, "重新產生草稿") else UiStrings.get(StringsRes.string.feature_player_draft_a_follow_up, "產生跟催草稿")) }
				TextButton(onClick = { showStyle = !showStyle }) { Text(if (showStyle) UiStrings.get(StringsRes.string.feature_player_hide_format_instructions, "隱藏格式要求") else UiStrings.get(StringsRes.string.feature_player_format_instructions, "格式要求")) }
			}
			if (viewModel.generating) {
				LinearProgressIndicator(Modifier.fillMaxWidth())
				TextButton(onClick = viewModel::cancel) { Text(stringResource(StringsRes.string.feature_player_cancel_generation)) }
			}
			viewModel.message?.let { msg ->
				Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
				TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(StringsRes.string.feature_player_got_it)) }
			}
		}
	}

	if (confirm) {
		val materials = viewModel.materials
		AlertDialog(
			onDismissRequest = { confirm = false },
			title = { Text(stringResource(StringsRes.string.feature_player_generate_follow_up_draft)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(
						stringResource(StringsRes.string.feature_player_the_following_text_will_be_sent_once) +
							(if (materials?.summary != null) UiStrings.get(StringsRes.string.feature_player_completed_summary, "已完成的摘要") else "") +
							(if (materials?.summary != null && materials.actionItems.isNotEmpty()) "、" else "") +
							(if (materials?.actionItems?.isNotEmpty() == true) UiStrings.get(StringsRes.string.feature_player_action_items, "%1\$s 項待辦", materials.actionItems.size) else "") + "。"
					)
					Text(stringResource(StringsRes.string.feature_player_audio_and_the_full_transcript_stay_on))
					if (materials?.truncated == true) Text(stringResource(StringsRes.string.feature_player_the_source_was_shortened_to_fit_the), color = MaterialTheme.colorScheme.secondary)
					if (viewModel.savedDraft != null) Text(stringResource(StringsRes.string.feature_player_a_successful_result_replaces_the_local_draft), color = MaterialTheme.colorScheme.secondary)
				}
			},
			confirmButton = { TextButton({ confirm = false; viewModel.generate() }) { Text(stringResource(StringsRes.string.feature_player_send_and_generate)) } },
			dismissButton = { TextButton({ confirm = false }) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
		)
	}
	if (confirmDiscard) {
		AlertDialog(
			onDismissRequest = { confirmDiscard = false },
			title = { Text(stringResource(StringsRes.string.feature_player_discard_draft)) },
			text = { Text(stringResource(StringsRes.string.feature_player_delete_this_recording_s_local_follow_up)) },
			confirmButton = { TextButton({ confirmDiscard = false; viewModel.discard() }) { Text(stringResource(StringsRes.string.feature_player_discard)) } },
			dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(StringsRes.string.feature_player_cancel)) } },
		)
	}
}
