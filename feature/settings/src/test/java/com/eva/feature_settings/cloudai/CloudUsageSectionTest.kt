package com.eva.feature_settings.cloudai

import com.eva.cloudtranscribe.model.MonthUsage
import com.eva.feature_settings.composables.cloudai.usageLine
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudUsageSectionTest {
	@Test
	fun line_shows_all_counters() {
		assertEquals(
			"2026-11：雲端轉錄 2 次・上傳約 12.5 分鐘・請求 5 次・摘要 1 次",
			usageLine(MonthUsage(YearMonth.of(2026, 11), 2, 750_000, 5, 1)),
		)
	}
}
