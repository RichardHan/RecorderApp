package com.eva.feature_player.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.eva.cloudtranscribe.chat.ChatCitation
import com.eva.cloudtranscribe.chat.ChatEstimate
import com.eva.cloudtranscribe.chat.ChatMessageRole
import com.eva.cloudtranscribe.chat.RecordingChatMessage
import com.eva.feature_player.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecordingChatScreenTest {
	@get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

	@Test
	fun first_question_requires_confirmation_and_reply_can_seek_and_be_cleared() {
		val history = mutableStateOf(emptyList<RecordingChatMessage>())
		val confirmed = mutableStateOf(false)
		val sent = mutableListOf<String>()
		var seekMs: Long? = null
		val question = compose.activity.getString(R.string.feature_player_chat_suggest_key_points)
		compose.setContent {
			MaterialTheme {
				RecordingChatScreen(
					recordingTitle = "Sample", loading = false, messages = history.value,
					sending = false, pendingQuestion = null, errorMessage = null,
					transcriptBlockedReason = null, configBlockedReason = null, contextInfo = null,
					estimate = ChatEstimate(900, 250, "sample-model", 0.01, true),
					recordedUsage = null, activeModel = "sample-model", confirmedCost = confirmed.value,
					onBack = {}, onNavigateToSettings = {}, onSeekTo = { seekMs = it },
					onSend = { _, _ -> error("first send requires confirmation") },
					onConfirmCostAndSend = { text, onSent ->
						confirmed.value = true
						sent += text
						history.value = listOf(RecordingChatMessage("a1", 5L, "t1", ChatMessageRole.ASSISTANT,
							"Friday", listOf(ChatCitation(2, 5000L)), createdAtMillis = 1))
						onSent()
					},
					onRefreshEstimate = {}, onCancelSend = {}, onClearHistory = { history.value = emptyList() },
					onDismissError = {},
				)
			}
		}
		fun label(id: Int) = compose.activity.getString(id)
		compose.onNodeWithText(question).performScrollTo().performClick()
		compose.onNodeWithText(label(R.string.feature_player_chat_confirm_title)).assertIsDisplayed()
		compose.onNodeWithText(label(R.string.feature_player_cancel)).performClick()
		compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
		compose.onNodeWithContentDescription(label(R.string.feature_player_chat_send)).performClick()
		compose.onNodeWithText(label(R.string.feature_player_chat_confirm_send)).performClick()
		compose.onNodeWithText("S2 00:05").performClick()
		compose.runOnIdle {
			assertEquals(listOf(question), sent)
			assertEquals(5000L, seekMs)
		}
		compose.onNodeWithContentDescription(label(R.string.feature_player_chat_clear)).performClick()
		compose.onNodeWithText(label(R.string.feature_player_chat_clear_confirm)).performClick()
		compose.onNodeWithText("Friday").assertDoesNotExist()
	}
}
