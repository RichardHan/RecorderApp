package com.eva.feature_settings.composables.cloudai

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.feature_settings.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The pre-meeting notes field: capped input, clear button only while there is text, and a plain string out. */
class MeetingNotesFieldTest {

	@get:Rule
	val compose = createAndroidComposeRule<ComponentActivity>()

	private val clearLabel get() = compose.activity.getString(R.string.live_cloud_meeting_notes_clear)

	@Test
	fun typingIsCappedAndTheClearButtonEmptiesTheField() {
		compose.setContent {
			MaterialTheme {
				var notes by remember { mutableStateOf("") }
				MeetingNotesField(notes) { notes = it }
			}
		}

		compose.onNodeWithText(clearLabel).assertDoesNotExist()
		// one replacement instead of 2250 IME keystrokes: same onValueChange contract, far cheaper on the emulator
		compose.onNode(hasSetTextAction()).performTextReplacement("x".repeat(LiveCloudSettings.MAX_MEETING_NOTES + 250))
		assertEquals(LiveCloudSettings.MAX_MEETING_NOTES, fieldText().length)

		compose.onNodeWithText(clearLabel).assertIsDisplayed().performClick()
		assertEquals("", fieldText())
		compose.onNodeWithText(clearLabel).assertDoesNotExist()
	}

	@Test
	fun shortNotesAreKeptVerbatim() {
		compose.setContent {
			MaterialTheme {
				var notes by remember { mutableStateOf("") }
				MeetingNotesField(notes) { notes = it }
			}
		}

		compose.onNode(hasSetTextAction()).performTextInput("議題：Q3 報價")
		assertEquals("議題：Q3 報價", fieldText())
	}

	private fun fieldText(): String =
		compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
}
