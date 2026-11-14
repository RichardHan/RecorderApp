package com.eva.feature_player.cloud

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Chapter rows: the time label is formatted from the stored ms, tapping jumps, delete reports the row. */
class ChaptersListTest {

	@get:Rule
	val compose = createAndroidComposeRule<ComponentActivity>()

	private val rows = listOf(
		ChapterRowUi(1, "開場", 0),
		ChapterRowUi(2, "報價討論", 192_000),
	)

	@Test
	fun rowsShowRealTimesAndTappingSeeks() {
		var seeked: Long? = null
		compose.setContent {
			MaterialTheme {
				ChaptersList(rows = rows, onSeek = { seeked = it }, onRename = { _, _ -> }, onDelete = {})
			}
		}

		compose.onNodeWithText("0:00").assertIsDisplayed()
		compose.onNodeWithText("3:12").assertIsDisplayed()
		compose.onNodeWithText("報價討論").performClick()
		assertEquals(192_000L, seeked)
	}

	@Test
	fun deleteReportsTheRow() {
		var deleted: Long? = null
		compose.setContent {
			MaterialTheme {
				ChaptersList(rows = rows, onSeek = {}, onRename = { _, _ -> }, onDelete = { deleted = it })
			}
		}
		compose.onAllNodesWithContentDescription("刪除章節").fetchSemanticsNodes()
		compose.onAllNodesWithContentDescription("刪除章節")[0].performClick()
		assertEquals(1L, deleted)
	}

	@Test
	fun formattedTimesUseMinutesAndSeconds() {
		assertEquals("0:00", formatChapterTime(0))
		assertEquals("1:05", formatChapterTime(65_000))
		assertEquals("10:00", formatChapterTime(600_000))
	}
}
