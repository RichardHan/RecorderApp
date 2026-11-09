package com.eva.feature_player.cloud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import com.eva.cloudtranscribe.model.*
import com.eva.player.domain.model.PlayerTrackData
import org.junit.Rule
import org.junit.Test

class TranscriptSearchComposeTest {
	@get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

	@Test fun singleMatchCanBeRevisitedAfterManualScroll() {
		val record = TranscriptRecord("test", 1, TranscriptSource.OFFLINE,
			Provenance("offline", model = "test"), 1, status = RunStatus.SUCCESS,
			text = "meeting", segments = (0 until 60).map {
				SegmentResult(it, it * 1000L, (it + 1) * 1000L, SegmentStatus.OK,
					if (it == 2) "needle" else "Other paragraph $it")
			})
		compose.setContent {
			MaterialTheme { TranscriptLines(record, { PlayerTrackData() }, null, Modifier.fillMaxSize()) }
		}
		compose.onNodeWithTag("transcript-search-input").performTextInput("needle")
		compose.waitForIdle()
		compose.onNodeWithTag("transcript-line-2").assertIsDisplayed()
		compose.onNodeWithTag("transcript-search-lines").performScrollToIndex(50)
		compose.onNodeWithText("下一筆").performClick()
		compose.waitForIdle()
		compose.onNodeWithTag("transcript-line-2").assertIsDisplayed()
	}
}
