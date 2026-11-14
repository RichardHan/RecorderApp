package com.eva.feature_player.cloud

import com.eva.cloudtranscribe.model.SpeechPaceReport
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPaceCardTest {
	@Test
	fun summary_mentions_pauses_or_missing_timestamps() {
		assertEquals("共 300 字・平均每分鐘 150 字（含段內停頓）", paceSummary(SpeechPaceReport(300, 150.7, null, null, emptyList(), 0.0)))
		assertEquals("共 5 字・沒有時間戳，無法計算語速", paceSummary(SpeechPaceReport(5, null, null, null, emptyList(), 0.0)))
		assertEquals("1:00:05", paceClock(3_605_000))
		assertEquals("03:12", paceClock(192_000))
	}
}
