package com.eva.cloudtranscribe.title

import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTitlePolicyTests {

	private val usable = SummaryRecord("s", 1, "t", Provenance(engine = "cloud-summary", model = "m"), 1L, RunStatus.SUCCESS, "- 週會討論")

	@Test
	fun attempts_only_when_on_unapplied_original_and_usable() {
		assertTrue(AutoTitlePolicy.shouldAttempt(true, false, "Voice-20 Nov 15:30.m4a", usable))
		assertFalse(AutoTitlePolicy.shouldAttempt(false, false, "Voice-20 Nov 15:30.m4a", usable))
		assertFalse(AutoTitlePolicy.shouldAttempt(true, true, "Voice-20 Nov 15:30.m4a", usable))
		assertFalse(AutoTitlePolicy.shouldAttempt(true, false, "我改過的名字.m4a", usable))
		assertFalse(AutoTitlePolicy.shouldAttempt(true, false, "Voice-20 Nov 15:30.m4a", null))
	}

	@Test
	fun prefers_ai_title_then_falls_back_to_summary_line() {
		assertEquals("週會討論" to AutoTitlePolicy.SOURCE_AI, AutoTitlePolicy.chooseTitle("週會討論", "- 其他重點"))
		assertEquals("Q3 報價上限五十萬" to AutoTitlePolicy.SOURCE_SUMMARY, AutoTitlePolicy.chooseTitle(null, "## 重點\n- Q3 報價上限五十萬"))
		assertEquals("Q3 報價上限五十萬" to AutoTitlePolicy.SOURCE_SUMMARY, AutoTitlePolicy.chooseTitle("好", "- Q3 報價上限五十萬"))
		assertNull(AutoTitlePolicy.chooseTitle(null, "目前無重點"))
		assertNull(AutoTitlePolicy.chooseTitle("x".repeat(41), "目前無重點"))
	}

	@Test
	fun setting_defaults_on_including_old_json() {
		assertTrue(CloudSettings().autoTitleAfterSummary)
		assertTrue(Json { ignoreUnknownKeys = true }.decodeFromString(CloudSettings.serializer(), """{"baseUrl":"https://x"}""").autoTitleAfterSummary)
	}
}
