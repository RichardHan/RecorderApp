package com.eva.feature_recordings.badges

import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingBadgesTests {
	private val prov = Provenance(engine = "e", model = "m")
	private fun t(status: RunStatus, text: String = "x", source: TranscriptSource = TranscriptSource.CLOUD) =
		TranscriptRecord("t$status$text", 1, source, prov, 0, status = status, text = text)
	private fun s(status: RunStatus, text: String) = SummaryRecord("s", 1, "t", prov, 0, status, text)

	@Test
	fun nothing_means_no_extra_line() {
		assertEquals(emptyList<String>(), RecordingBadges.of(emptyList(), emptyList(), 0).labels)
	}

	@Test
	fun all_states_in_order() {
		val b = RecordingBadges.of(listOf(t(RunStatus.SUCCESS, source = TranscriptSource.OFFLINE)), listOf(s(RunStatus.SUCCESS, "- a")), 3)
		assertEquals(listOf("逐字稿", "摘要", "待辦 3"), b.labels)
	}

	@Test
	fun failed_or_cancelled_transcripts_do_not_count_and_running_shows_progress() {
		assertEquals(emptyList<String>(), RecordingBadges.of(listOf(t(RunStatus.FAILED), t(RunStatus.CANCELLED)), emptyList(), 0).labels)
		assertEquals(listOf("轉錄中"), RecordingBadges.of(listOf(t(RunStatus.RUNNING, "")), emptyList(), 0).labels)
		// a usable transcript wins over a new run in progress
		assertEquals(listOf("逐字稿"), RecordingBadges.of(listOf(t(RunStatus.PARTIAL), t(RunStatus.QUEUED, "")), emptyList(), 0).labels)
		assertEquals(emptyList<String>(), RecordingBadges.of(listOf(t(RunStatus.SUCCESS, "")), emptyList(), 0).labels)
	}

	@Test
	fun summary_rules() {
		assertEquals(listOf("摘要"), RecordingBadges.of(emptyList(), listOf(s(RunStatus.SUCCESS, "目前無重點")), 0).labels)
		assertEquals(emptyList<String>(), RecordingBadges.of(emptyList(), listOf(s(RunStatus.SUCCESS, " "), s(RunStatus.FAILED, "- a")), 0).labels)
	}

	@Test
	fun cap_is_three_hundred() {
		assertEquals(300, RecordingBadges.MAX_RECORDINGS)
	}
}
