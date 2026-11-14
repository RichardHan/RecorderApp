package com.eva.cloudtranscribe.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSummaryPolicyTests {
	private val on = CloudSettings(autoSummaryAfterTranscript = true)
	private fun summary(transcriptId: String, status: RunStatus) =
		SummaryRecord("s-$transcriptId-$status", 1L, transcriptId, Provenance(engine = "cloud", model = "m"), 0L, status)

	@Test
	fun starts_only_for_successful_non_blank_run_with_opt_in() {
		assertTrue(AutoSummaryPolicy.shouldStart(on, RunStatus.SUCCESS, "內容", "t1", emptyList()))
		assertFalse(AutoSummaryPolicy.shouldStart(CloudSettings(), RunStatus.SUCCESS, "內容", "t1", emptyList()))
		listOf(RunStatus.PARTIAL, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.RUNNING).forEach {
			assertFalse(AutoSummaryPolicy.shouldStart(on, it, "內容", "t1", emptyList()))
		}
		assertFalse(AutoSummaryPolicy.shouldStart(on, RunStatus.SUCCESS, "  \n", "t1", emptyList()))
	}

	@Test
	fun never_twice_for_same_transcript_but_cancelled_or_other_transcript_does_not_block() {
		assertFalse(AutoSummaryPolicy.shouldStart(on, RunStatus.SUCCESS, "內容", "t1", listOf(summary("t1", RunStatus.SUCCESS))))
		assertFalse(AutoSummaryPolicy.shouldStart(on, RunStatus.SUCCESS, "內容", "t1", listOf(summary("t1", RunStatus.FAILED))))
		assertFalse(AutoSummaryPolicy.shouldStart(on, RunStatus.SUCCESS, "內容", "t1", listOf(summary("t1", RunStatus.QUEUED))))
		assertTrue(AutoSummaryPolicy.shouldStart(on, RunStatus.SUCCESS, "內容", "t1", listOf(summary("t1", RunStatus.CANCELLED), summary("t0", RunStatus.SUCCESS))))
	}

	@Test
	fun old_settings_json_defaults_off() {
		assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString(CloudSettings.serializer(), """{"baseUrl":"https://x"}""").autoSummaryAfterTranscript)
	}
}
