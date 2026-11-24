package com.eva.feature_player.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.chat.ChatEstimate
import com.eva.cloudtranscribe.chat.ChatMessageRole
import com.eva.cloudtranscribe.chat.RecordingChat
import com.eva.cloudtranscribe.chat.RecordingChatContext
import com.eva.cloudtranscribe.chat.RecordingChatMessage
import com.eva.cloudtranscribe.qa.RecordingQa
import com.eva.cloudtranscribe.usage.PeriodUsage
import com.eva.cloudtranscribe.usage.TokenPricing
import com.eva.feature_player.R as StringsRes
import com.eva.ui.R as CoreUiRes

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RecordingChatScreen(
	recordingTitle: String,
	loading: Boolean,
	messages: List<RecordingChatMessage>,
	sending: Boolean,
	pendingQuestion: String?,
	errorMessage: String?,
	transcriptBlockedReason: String?,
	configBlockedReason: String?,
	contextInfo: RecordingChatContext?,
	estimate: ChatEstimate?,
	recordedUsage: PeriodUsage?,
	activeModel: String,
	confirmedCost: Boolean,
	onBack: () -> Unit,
	onNavigateToSettings: () -> Unit,
	onSeekTo: (Long) -> Unit,
	onSend: (question: String, onSent: () -> Unit) -> Unit,
	onConfirmCostAndSend: (question: String, onSent: () -> Unit) -> Unit,
	onRefreshEstimate: (String) -> Unit,
	onCancelSend: () -> Unit,
	onClearHistory: () -> Unit,
	onDismissError: () -> Unit,
	modifier: Modifier = Modifier,
) {
	var input by rememberSaveable { mutableStateOf("") }
	var pendingConfirmQuestion by remember { mutableStateOf<String?>(null) }
	var showClearDialog by remember { mutableStateOf(false) }
	val listState = rememberLazyListState()

	LaunchedEffect(messages.size, pendingQuestion) {
		val count = messages.size + if (pendingQuestion != null) 2 else 0
		if (count > 0) listState.animateScrollToItem(count - 1)
	}

	val submitQuestion: (String) -> Unit = { raw ->
		val trimmed = raw.trim().take(RecordingChat.MAX_QUESTION_CHARS)
		if (trimmed.isNotEmpty() && !loading && !sending && transcriptBlockedReason == null && configBlockedReason == null) {
			if (confirmedCost) {
				onSend(trimmed) { if (raw == input) input = "" }
			} else {
				onRefreshEstimate(trimmed)
				pendingConfirmQuestion = trimmed
			}
		}
	}

	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(CoreUiRes.string.back_arrow),
						)
					}
				},
				title = {
					Column {
						Text(
							text = stringResource(StringsRes.string.feature_player_chat_title),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						val subtitle = buildList {
							if (recordingTitle.isNotBlank()) add(recordingTitle)
							if (activeModel.isNotBlank()) add(activeModel)
						}.joinToString(" · ")
						if (subtitle.isNotBlank()) {
							Text(
								text = subtitle,
								style = MaterialTheme.typography.labelSmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				},
				actions = {
					if (messages.isNotEmpty()) {
						IconButton(
							onClick = { showClearDialog = true },
							enabled = !sending,
						) {
							Icon(
								imageVector = Icons.Default.Delete,
								contentDescription = stringResource(StringsRes.string.feature_player_chat_clear),
							)
						}
					}
				},
			)
		},
		bottomBar = {
			if (!loading && transcriptBlockedReason == null) {
				ChatInputBar(
					value = input,
					onValueChange = { input = it.take(RecordingChat.MAX_QUESTION_CHARS) },
					enabled = !sending && configBlockedReason == null,
					canSend = input.isNotBlank() && !sending && configBlockedReason == null,
					onSendClick = { submitQuestion(input) },
				)
			}
		},
	) { paddingValues ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(paddingValues)
				.padding(horizontal = 12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			if (loading) {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					CircularProgressIndicator()
				}
				return@Scaffold
			}

			if (transcriptBlockedReason != null) {
				Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
					Card(Modifier.fillMaxWidth().padding(16.dp)) {
						Column(
							modifier = Modifier.padding(16.dp),
							verticalArrangement = Arrangement.spacedBy(8.dp),
						) {
							Text(
								text = stringResource(StringsRes.string.feature_player_chat_no_transcript_title),
								style = MaterialTheme.typography.titleMedium,
							)
							Text(
								text = transcriptBlockedReason,
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							Text(
								text = stringResource(StringsRes.string.feature_player_chat_no_transcript_body),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							OutlinedButton(onClick = onBack) {
								Text(stringResource(StringsRes.string.feature_player_chat_back_to_player))
							}
						}
					}
				}
				return@Scaffold
			}

			contextInfo?.let { ctx ->
				ContextStrip(context = ctx)
			}

			if (configBlockedReason != null) {
				ConfigGuideCard(
					reason = configBlockedReason,
					onGoToSettings = onNavigateToSettings,
				)
			}

			errorMessage?.let { err ->
				Surface(
					color = MaterialTheme.colorScheme.errorContainer,
					shape = MaterialTheme.shapes.medium,
					modifier = Modifier.fillMaxWidth(),
				) {
					Row(
						modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Text(
							text = err,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onErrorContainer,
							modifier = Modifier.weight(1f),
						)
						TextButton(onClick = onDismissError) {
							Text(stringResource(StringsRes.string.feature_player_got_it))
						}
					}
				}
			}

			LazyColumn(
				state = listState,
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentPadding = PaddingValues(vertical = 8.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				if (messages.isEmpty() && pendingQuestion == null) {
					if (configBlockedReason == null && estimate != null) {
						item(key = "pre-send-cost") {
							PreSendCostCard(
								estimate = estimate,
								recordedUsage = recordedUsage,
							)
						}
					}
					if (configBlockedReason == null) {
						item(key = "suggested-questions") {
							SuggestedQuestionsSection(
								enabled = !sending,
								onSelect = { suggestion ->
									input = suggestion
									submitQuestion(suggestion)
								},
							)
						}
					}
				}

				items(messages, key = { it.id }) { msg ->
					ChatMessageBubble(
						message = msg,
						onSeekTo = onSeekTo,
					)
				}

				if (pendingQuestion != null) {
					item(key = "pending-user") {
						UserBubble(text = pendingQuestion)
					}
					item(key = "pending-assistant") {
						ThinkingBubble(onCancel = onCancelSend)
					}
				}
			}
		}
	}

	pendingConfirmQuestion?.let { question ->
		AlertDialog(
			onDismissRequest = { pendingConfirmQuestion = null },
			title = { Text(stringResource(StringsRes.string.feature_player_chat_confirm_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text(
						text = stringResource(StringsRes.string.feature_player_chat_cost_text_only),
						style = MaterialTheme.typography.bodySmall,
					)
					estimate?.let { est ->
						Text(
							text = stringResource(
								StringsRes.string.feature_player_chat_estimate_tokens,
								TokenPricing.formatTokens(est.promptTokens),
								est.model,
							),
							style = MaterialTheme.typography.bodySmall,
						)
						val costLine = est.estimatedCostUsd?.let { cost ->
							stringResource(
								StringsRes.string.feature_player_chat_estimate_cost_usd,
								TokenPricing.formatCost(cost),
							)
						} ?: stringResource(StringsRes.string.feature_player_chat_estimate_cost_unset)
						Text(text = costLine, style = MaterialTheme.typography.bodySmall)
					}
					contextInfo?.coverageNote?.let { note ->
						Text(
							text = "⚠ " + stringResource(StringsRes.string.feature_player_chat_truncated_banner, note),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.error,
						)
					}
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						pendingConfirmQuestion = null
						onConfirmCostAndSend(question) {
							if (question == input.trim()) input = ""
						}
					},
				) {
					Text(stringResource(StringsRes.string.feature_player_chat_confirm_send))
				}
			},
			dismissButton = {
				TextButton(onClick = { pendingConfirmQuestion = null }) {
					Text(stringResource(StringsRes.string.feature_player_cancel))
				}
			},
		)
	}

	if (showClearDialog) {
		AlertDialog(
			onDismissRequest = { showClearDialog = false },
			title = { Text(stringResource(StringsRes.string.feature_player_chat_clear_title)) },
			text = { Text(stringResource(StringsRes.string.feature_player_chat_clear_body)) },
			confirmButton = {
				TextButton(
					onClick = {
						showClearDialog = false
						onClearHistory()
					},
				) {
					Text(stringResource(StringsRes.string.feature_player_chat_clear_confirm))
				}
			},
			dismissButton = {
				TextButton(onClick = { showClearDialog = false }) {
					Text(stringResource(StringsRes.string.feature_player_cancel))
				}
			},
		)
	}
}

@Composable
private fun ContextStrip(context: RecordingChatContext) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		val badgeText = if (context.hasSummary) {
			stringResource(StringsRes.string.feature_player_chat_context_with_summary, context.transcriptLabel)
		} else {
			stringResource(StringsRes.string.feature_player_chat_context_badge, context.transcriptLabel)
		}
		Text(
			text = badgeText,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		context.coverageNote?.let { note ->
			Surface(
				color = MaterialTheme.colorScheme.secondaryContainer,
				shape = MaterialTheme.shapes.small,
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(
					text = "⚠ " + stringResource(StringsRes.string.feature_player_chat_truncated_banner, note),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSecondaryContainer,
					modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
				)
			}
		}
	}
}

@Composable
private fun ConfigGuideCard(
	reason: String,
	onGoToSettings: () -> Unit,
) {
	Card(
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = stringResource(StringsRes.string.feature_player_chat_config_needed_title),
				style = MaterialTheme.typography.titleSmall,
			)
			Text(
				text = reason,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.error,
			)
			Button(onClick = onGoToSettings) {
				Text(stringResource(StringsRes.string.feature_player_chat_go_to_settings))
			}
		}
	}
}

@Composable
private fun PreSendCostCard(
	estimate: ChatEstimate,
	recordedUsage: PeriodUsage?,
) {
	Card(
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
		modifier = Modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier.padding(14.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Text(
				text = stringResource(StringsRes.string.feature_player_chat_cost_card_title),
				style = MaterialTheme.typography.titleSmall,
			)
			Text(
				text = stringResource(StringsRes.string.feature_player_chat_cost_text_only),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				text = stringResource(
					StringsRes.string.feature_player_chat_estimate_tokens,
					TokenPricing.formatTokens(estimate.promptTokens),
					estimate.model,
				),
				style = MaterialTheme.typography.bodySmall,
			)
			val costText = estimate.estimatedCostUsd?.let { cost ->
				stringResource(
					StringsRes.string.feature_player_chat_estimate_cost_usd,
					TokenPricing.formatCost(cost),
				)
			} ?: stringResource(StringsRes.string.feature_player_chat_estimate_cost_unset)
			Text(
				text = costText,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (recordedUsage != null && recordedUsage.tokenRequests > 0) {
				Text(
					text = stringResource(
						StringsRes.string.feature_player_chat_recorded_usage,
						TokenPricing.formatTokens(recordedUsage.promptTokens),
						TokenPricing.formatTokens(recordedUsage.completionTokens),
					),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
private fun SuggestedQuestionsSection(
	enabled: Boolean,
	onSelect: (String) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(
			text = stringResource(StringsRes.string.feature_player_chat_empty_hint),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = stringResource(StringsRes.string.feature_player_chat_suggested_heading),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			listOf(
				StringsRes.string.feature_player_chat_suggest_key_points,
				StringsRes.string.feature_player_chat_suggest_tasks,
				StringsRes.string.feature_player_chat_suggest_decisions,
			).forEach { res ->
				val q = stringResource(res)
				AssistChip(
					enabled = enabled,
					onClick = { onSelect(q) },
					label = { Text(q) },
				)
			}
		}
	}
}

@Composable
private fun ChatMessageBubble(
	message: RecordingChatMessage,
	onSeekTo: (Long) -> Unit,
) {
	when (message.role) {
		ChatMessageRole.USER -> UserBubble(text = message.text)
		ChatMessageRole.ASSISTANT -> AssistantBubble(message = message, onSeekTo = onSeekTo)
	}
}

@Composable
private fun UserBubble(text: String) {
	Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
		Surface(
			color = MaterialTheme.colorScheme.primaryContainer,
			shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
			modifier = Modifier.widthIn(max = 320.dp),
		) {
			SelectionContainer {
				Text(
					text = text,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
					modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
				)
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantBubble(
	message: RecordingChatMessage,
	onSeekTo: (Long) -> Unit,
) {
	Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
		Surface(
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
			modifier = Modifier.widthIn(max = 340.dp),
		) {
			Column(
				modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				SelectionContainer {
					Text(
						text = message.text,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				if (message.citations.isNotEmpty()) {
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						message.citations.forEach { citation ->
							val start = citation.startMs
							AssistChip(
								enabled = start != null,
								onClick = { if (start != null) onSeekTo(start) },
								label = {
									Text(
										start?.let { "S${citation.segmentId} ${RecordingQa.clock(it)}" }
											?: "S${citation.segmentId}",
										style = MaterialTheme.typography.labelSmall,
									)
								},
							)
						}
					}
				}
				if (message.hasUnverifiedCitations) {
					Text(
						text = stringResource(StringsRes.string.feature_player_chat_unverified_citations),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
				message.coverageNote?.let { note ->
					Text(
						text = "⚠ $note",
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}
}

@Composable
private fun ThinkingBubble(onCancel: () -> Unit) {
	Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
		Surface(
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
		) {
			Row(
				modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp),
			) {
				CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
				Text(
					text = stringResource(StringsRes.string.feature_player_chat_thinking),
					style = MaterialTheme.typography.bodyMedium,
				)
				TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
					Text(stringResource(StringsRes.string.feature_player_cancel))
				}
			}
		}
	}
}

@Composable
private fun ChatInputBar(
	value: String,
	onValueChange: (String) -> Unit,
	enabled: Boolean,
	canSend: Boolean,
	onSendClick: () -> Unit,
) {
	Surface(
		tonalElevation = 2.dp,
		modifier = Modifier
			.fillMaxWidth()
			.imePadding()
			.navigationBarsPadding(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 12.dp, vertical = 8.dp),
			verticalAlignment = Alignment.Bottom,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			OutlinedTextField(
				value = value,
				onValueChange = onValueChange,
				enabled = enabled,
				placeholder = { Text(stringResource(StringsRes.string.feature_player_chat_input_hint)) },
				maxLines = 4,
				modifier = Modifier.weight(1f),
			)
			Spacer(Modifier.size(0.dp))
			FilledIconButton(
				onClick = onSendClick,
				enabled = canSend,
			) {
				Icon(
					imageVector = Icons.AutoMirrored.Filled.Send,
					contentDescription = stringResource(StringsRes.string.feature_player_chat_send),
				)
			}
		}
	}
}
