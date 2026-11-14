package com.eva.feature_player.cloud

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** To-do rows: the checkbox reports the new value, tapping the text opens editing, delete reports the item. */
class ActionItemsListContentTest {

	@get:Rule
	val compose = createAndroidComposeRule<ComponentActivity>()

	private fun item(id: Long, text: String, done: Boolean = false) = ActionItemUi(id, text, done)

	@Test
	fun deletingReportsTheItem() {
		var deleted: Long? = null
		compose.setContent {
			MaterialTheme {
				ActionItemsListContent(
					items = listOf(item(1, "寫報價單")),
					editingId = null,
					onToggle = { _, _ -> },
					onStartEdit = {},
					onSaveText = { _, _ -> },
					onDelete = { id -> deleted = id },
				)
			}
		}

		compose.onNodeWithText("寫報價單").assertIsDisplayed()
		compose.onNodeWithContentDescription("刪除待辦").performClick()
		assertEquals(1L, deleted)
	}

	@Test
	fun tappingTheTextOpensEditing() {
		var editing: Long? = null
		compose.setContent {
			MaterialTheme {
				var editingId by remember { mutableStateOf<Long?>(null) }
				ActionItemsListContent(
					items = listOf(item(5, "約會議")),
					editingId = editingId,
					onToggle = { _, _ -> },
					onStartEdit = { id -> editing = id; editingId = id },
					onSaveText = { _, _ -> },
					onDelete = {},
				)
			}
		}

		compose.onNodeWithText("約會議").performClick()
		assertEquals(5L, editing)
		compose.onNodeWithText("儲存").assertIsDisplayed()
	}

	@Test
	fun doneAndPendingItemsAreBothListed() {
		compose.setContent {
			MaterialTheme {
				ActionItemsListContent(
					items = listOf(item(1, "未完成"), item(2, "已完成", done = true)),
					editingId = null,
					onToggle = { _, _ -> },
					onStartEdit = {},
					onSaveText = { _, _ -> },
					onDelete = {},
				)
			}
		}
		compose.onNodeWithText("未完成").assertIsDisplayed()
		compose.onNodeWithText("已完成").assertIsDisplayed()
	}
}
