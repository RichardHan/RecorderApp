package com.eva.feature_settings.cloudai

import com.eva.cloudtranscribe.model.MonthUsage
import com.eva.cloudtranscribe.usage.ModelPrice
import com.eva.feature_settings.composables.cloudai.upsertPrice
import com.eva.feature_settings.composables.cloudai.usageArgs
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudUsageSectionTest {
	@Test
	fun usage_args_follow_the_string_placeholder_order() {
		// cloud_usage_month = "%1$s：… %2$d 筆・…約 %3$s 分鐘・… %4$d 次・… %5$d 筆"
		assertEquals(
			listOf("2026-11", 2, "12.5", 5, 1),
			usageArgs(MonthUsage(YearMonth.of(2026, 11), cloudTranscripts = 2, uploadedMs = 750_000, requests = 5, summaries = 1)),
		)
	}

	@Test
	fun upsert_price_updates_or_inserts_a_model_row() {
		val first = upsertPrice(emptyList(), "gemini-3.8-flash", "0.5", "3")
		assertEquals(listOf(ModelPrice("gemini-3.8-flash", "0.5", "3")), first)
		val second = upsertPrice(first, "GEMINI-3.8-FLASH", "1", "4")
		assertEquals(listOf(ModelPrice("gemini-3.8-flash", "1", "4")), second)
	}
}
