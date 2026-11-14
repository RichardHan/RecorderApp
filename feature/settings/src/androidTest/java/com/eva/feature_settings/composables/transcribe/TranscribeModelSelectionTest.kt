package com.eva.feature_settings.composables.transcribe

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.eva.feature_settings.utils.SpeechToTextUIModel
import com.eva.transcribe.domain.models.STTModel
import com.eva.transcribe.domain.models.STTModelState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** An installed local model stays selectable even while the live-caption (即時字幕) switch is off. */
class TranscribeModelSelectionTest {

	@get:Rule
	val compose = createAndroidComposeRule<ComponentActivity>()

	private val installed = STTModel(
		modelId = "installed-model", name = "installed", displayName = "Installed test model",
		engine = "sherpa-onnx", state = STTModelState.DOWNLOADED,
	)
	private val missing = STTModel(
		modelId = "missing-model", name = "missing", displayName = "Missing test model",
		engine = "sherpa-onnx", state = STTModelState.UN_AVAILABLE,
	)

	@Test
	fun installedModelCanBeSelectedWhileLiveCaptionsAreOff() {
		val selected = mutableListOf<String>()
		val invalid = mutableListOf<String>()
		compose.setContent {
			MaterialTheme {
				TranscribeSettingsTabContent(
					sttModels = persistentListOf(SpeechToTextUIModel(installed), SpeechToTextUIModel(missing)),
					isTranscriptionActive = false,
					onSelectSTTModel = { selected += it.modelId },
					onSelectInvalidModel = { invalid += it.modelId },
				)
			}
		}

		compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Installed test model", substring = true))
		compose.onNodeWithText("Installed test model", substring = true, useUnmergedTree = true).performClick()
		compose.waitForIdle()
		assertEquals(listOf("installed-model"), selected)

		// a not-installed model only shows its hint and does not touch the selection
		compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Missing test model", substring = true))
		compose.onNodeWithText("Missing test model", substring = true, useUnmergedTree = true).performClick()
		compose.waitForIdle()
		assertEquals(listOf("installed-model"), selected)
		assertEquals(listOf("missing-model"), invalid)
	}
}
