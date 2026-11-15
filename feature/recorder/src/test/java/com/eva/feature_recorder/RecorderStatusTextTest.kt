package com.eva.feature_recorder

import com.eva.cloudtranscribe.live.LiveCloudPhase
import com.eva.cloudtranscribe.live.LiveCloudState
import com.eva.feature_recorder.composable.cloudStatusText
import com.eva.feature_recorder.composable.liveStatusText
import com.eva.recorder.domain.recorder.LiveTranscriptState
import com.eva.recorder.domain.recorder.LiveTranscriptStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecorderStatusTextTest {

	@Test
	fun live_placeholder_is_short_listening() {
		val listening = LiveTranscriptState(status = LiveTranscriptStatus.LISTENING)
		assertEquals("聆聽中…", liveStatusText(listening, isActive = true))
		assertEquals("聆聽中…", liveStatusText(listening.copy(finalized = listOf("已有一句")), isActive = true))
	}

	@Test
	fun cloud_placeholder_omits_timing_and_model_names() {
		val waiting = LiveCloudState(phase = LiveCloudPhase.WAITING_FIRST_CHUNK, transcribeModel = "gemini-3.8-flash")
		val text = cloudStatusText(waiting)
		assertEquals("聆聽中…", text)
		assertFalse(text.contains("60"))
		assertFalse(text.contains("gemini"))
	}

	@Test
	fun idle_and_summarizing_stay_short() {
		assertEquals("聆聽中…", cloudStatusText(LiveCloudState(phase = LiveCloudPhase.IDLE, summaryModel = "deepseek")))
		assertEquals("整理重點中…", cloudStatusText(LiveCloudState(phase = LiveCloudPhase.SUMMARIZING)))
	}
}
