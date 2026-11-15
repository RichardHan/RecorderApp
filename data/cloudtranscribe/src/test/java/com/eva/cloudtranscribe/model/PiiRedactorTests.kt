package com.eva.cloudtranscribe.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PiiRedactorTests {
	@Test
	fun masks_each_type() {
		assertEquals("寄到 [Email] 就好", PiiRedactor.redact("寄到 amy.chen+x@example.com.tw 就好"))
		assertEquals("手機 [電話]、[電話]、[電話]", PiiRedactor.redact("手機 0912-345-678、0912345678、+886 912 345 678"))
		assertEquals("公司 [電話] 或 [電話]", PiiRedactor.redact("公司 02-2345-6789 或 (02)23456789"))
		assertEquals("證號 [證號]。", PiiRedactor.redact("證號 A123456789。"))
		assertEquals("卡 [卡號] 到期", PiiRedactor.redact("卡 4111 1111 1111 1111 到期"))
	}

	@Test
	fun leaves_ordinary_numbers_and_times_alone() {
		val text = "00:12:30 討論 2026-11-15 的報價 500000 元，訂單 1234567890123，第 3 季成長 12%"
		assertEquals(text, PiiRedactor.redact(text))
		assertFalse(PiiRedactor.luhn("4111111111111112"))
	}

	@Test
	fun setting_defaults_off() {
		assertFalse(CloudSettings().redactPiiForCloud)
		assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString(CloudSettings.serializer(), """{"baseUrl":"https://x"}""").redactPiiForCloud)
	}
}
