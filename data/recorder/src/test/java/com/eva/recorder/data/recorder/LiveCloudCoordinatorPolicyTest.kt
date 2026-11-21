package com.eva.recorder.data.recorder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCloudCoordinatorPolicyTest {
	@Test
	fun skipsAsrRefineWhenNoSummaryClientAndTranscribeOnlyModel() {
		assertTrue(LiveCloudCoordinator.shouldSkipAsrRefine("gemini-3.5-transcribe", speakerDiarization = false, hasSummaryClient = false))
		assertTrue(LiveCloudCoordinator.shouldSkipAsrRefine("gpt-4o-TRANSCRIBE", speakerDiarization = false, hasSummaryClient = false))
		assertTrue(LiveCloudCoordinator.shouldSkipAsrRefine("gemini-4-argon", speakerDiarization = true, hasSummaryClient = false))
		assertFalse(LiveCloudCoordinator.shouldSkipAsrRefine("gemini-4-argon", speakerDiarization = false, hasSummaryClient = false))
		assertFalse(LiveCloudCoordinator.shouldSkipAsrRefine("gemini-3.5-transcribe", speakerDiarization = true, hasSummaryClient = true))
	}
}
