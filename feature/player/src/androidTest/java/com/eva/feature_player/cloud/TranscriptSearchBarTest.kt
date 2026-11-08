package com.eva.feature_player.cloud

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.eva.feature_player.R
import org.junit.Rule
import org.junit.Test

/** The transcript search bar as the player uses it: counter, ↑/↓ stepping, and hiding while the box is empty. */
class TranscriptSearchBarTest {

	@get:Rule
	val compose = createAndroidComposeRule<ComponentActivity>()

	private val hits = 3

	@Test
	fun emptyQueryHidesCounterAndStepButtons() {
		compose.setContent { Host() }
		compose.onNodeWithText(compose.activity.getString(R.string.transcript_search_previous_arrow)).assertDoesNotExist()
		compose.onNodeWithText(compose.activity.getString(R.string.transcript_search_next_arrow)).assertDoesNotExist()
		compose.onNodeWithText("0/$hits").assertDoesNotExist()
		compose.onNode(hasSetTextAction()).assertIsDisplayed()
	}

	@Test
	fun typingShowsCounterAndSteppingMovesWithinMatches() {
		compose.setContent { Host() }

		compose.onNode(hasSetTextAction()).performTextInput("api")
		compose.onNodeWithText("1/$hits").assertIsDisplayed()

		val next = compose.onNodeWithContentDescription(compose.activity.getString(R.string.transcript_search_next))
		next.performClick()
		compose.onNodeWithText("2/$hits").assertIsDisplayed()
		next.performClick()
		compose.onNodeWithText("3/$hits").assertIsDisplayed()
		// wrapping: 3 → 1
		next.performClick()
		compose.onNodeWithText("1/$hits").assertIsDisplayed()

		compose.onNodeWithContentDescription(compose.activity.getString(R.string.transcript_search_previous)).performClick()
		compose.onNodeWithText("3/$hits").assertIsDisplayed()
	}

	@Test
	fun queryWithoutMatchesShowsZeroAndKeepsSteppingDisabled() {
		compose.setContent { Host(netHits = 0) }

		compose.onNode(hasSetTextAction()).performTextInput("zzz")
		compose.onNodeWithText("0/0").assertIsDisplayed()
		compose.onNodeWithContentDescription(compose.activity.getString(R.string.transcript_search_next)).performClick()
		compose.onNodeWithText("0/0").assertIsDisplayed()
	}

	@Test
	fun clearingTheQueryHidesResultsAgain() {
		compose.setContent { Host() }

		compose.onNode(hasSetTextAction()).performTextInput("api")
		compose.onNodeWithText("1/$hits").assertIsDisplayed()
		compose.onNode(hasSetTextAction()).performTextClearance()
		compose.onNodeWithText("1/$hits").assertDoesNotExist()
		compose.onNodeWithContentDescription(compose.activity.getString(R.string.transcript_search_next)).assertDoesNotExist()
	}

	/** Mirrors the player: the bar owns no state, the caller decides both the hits and the focused one. */
	@Composable
	private fun Host(netHits: Int = hits) {
		MaterialTheme {
			var query by remember { mutableStateOf("") }
			var position by remember { mutableIntStateOf(1) }
			TranscriptSearchBar(
				query = query,
				onQueryChange = { query = it; position = 1 },
				position = if (query.isBlank() || netHits == 0) 0 else position,
				count = if (query.isBlank()) 0 else netHits,
				onPrevious = { position = if (position <= 1) netHits else position - 1 },
				onNext = { position = if (position >= netHits) 1 else position + 1 },
			)
		}
	}
}
