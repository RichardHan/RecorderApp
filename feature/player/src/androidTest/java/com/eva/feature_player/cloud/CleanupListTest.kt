package com.eva.feature_player.cloud

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CleanupListTest {
	@get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

	@Test fun tapSeeksToOriginalSentenceTimeAndCompareShowsOriginal() {
		var seek: Long? = null
		val rows = listOf(CleanupRowUi(0, "我們開始。", "嗯 我們 我們開始", 0), CleanupRowUi(3, "下週交付。", "呃 下週交付", 4200))
		compose.setContent { MaterialTheme { CleanupList(rows, compare = true, onSeek = { seek = it }) } }
		compose.onNodeWithText("原文：呃 下週交付").assertIsDisplayed()
		compose.onNodeWithTag("cleanup-row-3").performClick()
		compose.waitForIdle()
		assertEquals(4200L, seek)
	}
}
