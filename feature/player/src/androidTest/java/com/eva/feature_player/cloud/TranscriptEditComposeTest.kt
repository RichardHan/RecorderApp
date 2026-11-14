package com.eva.feature_player.cloud

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.eva.cloudtranscribe.model.*
import com.eva.player.domain.model.PlayerTrackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TranscriptEditComposeTest {
	@get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

	private val record = TranscriptRecord("t", 1, TranscriptSource.OFFLINE, Provenance("offline", model = "m"), 1,
		status = RunStatus.SUCCESS, text = "x",
		segments = (0 until 3).map { SegmentResult(it, it * 1000L, (it + 1) * 1000L, SegmentStatus.OK, "第 $it 句 recoder") })

	@Test fun editModeTapEditsInsteadOfSeeking() {
		var seek: Long? = null
		var edited: String? = null
		compose.setContent {
			MaterialTheme {
				TranscriptLines(record, { PlayerTrackData() }, { seek = it }, Modifier.fillMaxSize(),
					editor = TranscriptLineEditor(editMode = true, onEditLine = { edited = it.key }, onReplaceInLine = { _, _ -> }))
			}
		}
		compose.onNodeWithTag("transcript-line-1").performClick()
		compose.waitForIdle()
		assertEquals("s1", edited)
		assertNull(seek)
	}

	@Test fun readModeTapStillSeeksToSavedStart() {
		var seek: Long? = null
		compose.setContent {
			MaterialTheme {
				TranscriptLines(record, { PlayerTrackData() }, { seek = it }, Modifier.fillMaxSize(),
					editor = TranscriptLineEditor(editMode = false, onEditLine = {}, onReplaceInLine = { _, _ -> }))
			}
		}
		compose.onNodeWithTag("transcript-line-2").performClick()
		compose.waitForIdle()
		assertEquals(2000L, seek)
	}

	@Test fun replaceCurrentMatchHandsTheMatchedLineAndQuery() {
		var replaced: Pair<String, String>? = null
		compose.setContent {
			MaterialTheme {
				TranscriptLines(record, { PlayerTrackData() }, null, Modifier.fillMaxSize(),
					editor = TranscriptLineEditor(editMode = true, onEditLine = {}, onReplaceInLine = { line, q -> replaced = line.key to q }))
			}
		}
		compose.onNodeWithTag("transcript-search-input").performTextInput("第 2")
		compose.waitForIdle()
		compose.onNodeWithTag("transcript-replace-current").performClick()
		compose.waitForIdle()
		assertEquals("s2" to "第 2", replaced)
	}
}
