package com.eva.cloudtranscribe.title

import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.models.RecordedVoiceModel
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTitleRenamerTests {

	private val at = LocalDateTime(2026, 11, 20, 15, 30, 0)
	private val settingsOn = CloudSettings(baseUrl = "https://example.test/v1", summaryModel = "summary-model", autoTitleAfterSummary = true)

	private fun recording(display: String, title: String = display) = RecordedVoiceModel(
		id = 7L, title = title, displayName = display, mimeType = "audio/mp4",
		modifiedAt = at, recordedAt = at, fileUri = "content://media/7",
	)

	private fun storeWithSummary(text: String, status: RunStatus = RunStatus.SUCCESS): TranscriptStore {
		val store = TranscriptStore(Files.createTempDirectory("auto-title").toFile())
		store.saveSummary(SummaryRecord("s1", 7L, "t1", Provenance(engine = "cloud-summary", model = "m"), 1L, status, text))
		return store
	}

	private fun renamer(
		store: TranscriptStore,
		settings: CloudSettings = settingsOn,
		hasKey: Boolean = true,
		complete: suspend (CloudSettings, String) -> CallOutcome = { _, _ -> CallOutcome.Text("週會討論") },
		recording: RecordedVoiceModel = recording("Voice-20 Nov 15:30.m4a"),
		rename: suspend (RecordedVoiceModel, String) -> Boolean = { _, _ -> true },
		now: Long = 1_794_708_000_000L,
	) = AutoTitleRenamer(
		store = store,
		settings = { settings },
		hasSummaryKey = { hasKey },
		complete = complete,
		loadRecording = { if (it == recording.id) recording else null },
		rename = rename,
		nowMillis = { now },
	)

	@Test
	fun skips_when_setting_is_off_and_does_not_mark() = runBlocking {
		val store = storeWithSummary("- 週會討論")
		val result = renamer(store, settings = settingsOn.copy(autoTitleAfterSummary = false)).applyIfEligible(7)
		assertEquals(AutoTitleResult.Skipped("setting-off"), result)
		assertEquals(null, store.autoTitleMarker(7))
	}

	@Test
	fun skips_manual_names_without_marking() = runBlocking {
		val store = storeWithSummary("- 週會討論")
		val result = renamer(store, recording = recording("我改過的名字.m4a")).applyIfEligible(7)
		assertEquals(AutoTitleResult.Skipped("not-original"), result)
		assertEquals(null, store.autoTitleMarker(7))
	}

	@Test
	fun skips_when_there_are_no_usable_points() = runBlocking {
		val store = storeWithSummary("目前無重點")
		assertEquals(AutoTitleResult.Skipped("no-points"), renamer(store).applyIfEligible(7))
		assertEquals(null, store.autoTitleMarker(7))
	}

	@Test
	fun renames_with_ai_title_and_only_once() = runBlocking {
		val store = storeWithSummary("- 其他重點")
		val renamed = mutableListOf<String>()
		val r = renamer(store, rename = { _, name -> renamed += name; true })
		val first = r.applyIfEligible(7)
		assertEquals(AutoTitleResult.Applied("2026-11-20 1530 週會討論.m4a", AutoTitlePolicy.SOURCE_AI), first)
		assertEquals(listOf("2026-11-20 1530 週會討論.m4a"), renamed)
		val marker = store.autoTitleMarker(7)!!
		assertTrue(marker.applied)
		assertEquals("Voice-20 Nov 15:30.m4a", marker.previousDisplayName)
		assertEquals(1, marker.requestCount)
		assertEquals(listOf(1_794_708_000_000L), marker.requestAtMillis)
		assertEquals(AutoTitleResult.Skipped("already"), r.applyIfEligible(7))
		assertEquals(1, renamed.size)
	}

	@Test
	fun falls_back_to_summary_when_ai_fails_and_counts_the_request() = runBlocking {
		val store = storeWithSummary("## 重點\n- Q3 報價上限五十萬")
		val result = renamer(store, complete = { _, _ -> CallOutcome.Failure("down", retryable = true) }).applyIfEligible(7)
		assertEquals(AutoTitleResult.Applied("2026-11-20 1530 Q3 報價上限五十萬.m4a", AutoTitlePolicy.SOURCE_SUMMARY), result)
		assertEquals(1, store.autoTitleMarker(7)!!.requestCount)
	}

	@Test
	fun does_not_call_or_count_ai_without_a_key_and_still_falls_back() = runBlocking {
		val store = storeWithSummary("- 週會討論")
		var calls = 0
		val result = renamer(store, hasKey = false, complete = { _, _ -> calls++; CallOutcome.Text("不該呼叫") }).applyIfEligible(7)
		assertEquals(0, calls)
		assertEquals(AutoTitleResult.Applied("2026-11-20 1530 週會討論.m4a", AutoTitlePolicy.SOURCE_SUMMARY), result)
		assertEquals(0, store.autoTitleMarker(7)!!.requestCount)
	}

	@Test
	fun does_not_mark_applied_when_both_titles_fail() = runBlocking {
		val store = storeWithSummary("- 。")
		val result = renamer(store, complete = { _, _ -> CallOutcome.Empty }).applyIfEligible(7)
		assertEquals(AutoTitleResult.Skipped("no-title"), result)
		val marker = store.autoTitleMarker(7)!!
		assertFalse(marker.applied)
		assertEquals(1, marker.requestCount)
	}

	@Test
	fun retries_name_collisions_with_existing_suffix_convention() = runBlocking {
		val store = storeWithSummary("- 週會討論")
		val tried = mutableListOf<String>()
		val result = renamer(store, complete = { _, _ -> CallOutcome.Text("週會討論") }, rename = { _, name ->
			tried += name
			name.endsWith(" (2).m4a")
		}).applyIfEligible(7)
		assertEquals(AutoTitleResult.Applied("2026-11-20 1530 週會討論 (2).m4a", AutoTitlePolicy.SOURCE_AI), result)
		assertEquals(listOf("2026-11-20 1530 週會討論.m4a", "2026-11-20 1530 週會討論 (2).m4a"), tried)
	}

	@Test
	fun redacts_pii_in_the_title_prompt_when_enabled() = runBlocking {
		val store = storeWithSummary("- 請打 0912-345-678 給我")
		var prompt = ""
		renamer(
			store,
			settings = settingsOn.copy(redactPiiForCloud = true),
			complete = { _, p -> prompt = p; CallOutcome.Text("週會討論") },
		).applyIfEligible(7)
		assertFalse(prompt.contains("0912-345-678"))
	}
	@Test
	fun ignores_live_keypoints_until_post_recording_summary_succeeds() = runBlocking {
		val store = TranscriptStore(Files.createTempDirectory("auto-title-live").toFile())
		store.saveSummary(SummaryRecord("live-1", 7L, "t0", Provenance(engine = "live-keypoints", model = "m"), 999L, RunStatus.SUCCESS, "- 即時重點"))
		var aiCalls = 0
		val r = renamer(store, complete = { _, _ -> aiCalls++; CallOutcome.Text("不該命名") })
		assertEquals(AutoTitleResult.Skipped("no-points"), r.applyIfEligible(7))
		assertEquals(0, aiCalls)
		assertEquals(null, store.autoTitleMarker(7))
	}

	@Test
	fun collision_suffix_preserves_empty_extension_when_title_contains_dot() = runBlocking {
		val store = storeWithSummary("- 報告重點")
		val tried = mutableListOf<String>()
		val result = renamer(
			store,
			recording = recording("Voice-20 Nov 15:30"),
			complete = { _, _ -> CallOutcome.Text("報告 v2.1") },
			rename = { _, name -> tried += name; name.endsWith(" (2)") },
		).applyIfEligible(7)
		assertEquals(AutoTitleResult.Applied("2026-11-20 1530 報告 v2.1 (2)", AutoTitlePolicy.SOURCE_AI), result)
		assertEquals(listOf("2026-11-20 1530 報告 v2.1", "2026-11-20 1530 報告 v2.1 (2)"), tried)
	}
}
