package com.eva.cloudtranscribe.work

import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.RunStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CloudDoneMessagesTests {

	@Test
	fun transcription_texts_and_no_notice_for_cancelled_or_running() {
		assertEquals("「週會」雲端轉錄完成", CloudDoneMessages.transcription(RunStatus.SUCCESS, "週會"))
		assertEquals("「週會」雲端轉錄部分完成（有段落失敗）", CloudDoneMessages.transcription(RunStatus.PARTIAL, "週會"))
		assertEquals("「錄音」雲端轉錄失敗", CloudDoneMessages.transcription(RunStatus.FAILED, null))
		assertNull(CloudDoneMessages.transcription(RunStatus.CANCELLED, "x"))
		assertNull(CloudDoneMessages.transcription(RunStatus.RUNNING, "x"))
		assertNull(CloudDoneMessages.transcription(RunStatus.QUEUED, "x"))
	}

	@Test
	fun summary_texts_use_first_point_and_cut_it() {
		assertEquals("「週會」摘要完成：報價上限五十萬", CloudDoneMessages.summary(RunStatus.SUCCESS, "週會", "## 重點\n- 報價上限五十萬\n- 其他"))
		assertEquals("「週會」摘要完成：目前無重點", CloudDoneMessages.summary(RunStatus.SUCCESS, "週會", "目前無重點"))
		assertEquals("「週會」摘要完成", CloudDoneMessages.summary(RunStatus.SUCCESS, "週會", ""))
		val long = CloudDoneMessages.summary(RunStatus.SUCCESS, "a", "- " + "字".repeat(100))!!
		assertEquals("「a」摘要完成：" + "字".repeat(CloudDoneMessages.MAX_POINT) + "…", long)
		assertEquals("「錄音」摘要失敗", CloudDoneMessages.summary(RunStatus.FAILED, " ", ""))
		assertNull(CloudDoneMessages.summary(RunStatus.CANCELLED, "a", ""))
	}

	@Test
	fun setting_is_off_by_default_and_for_old_json() {
		assertFalse(CloudSettings().notifyWhenDone)
		assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString(CloudSettings.serializer(), """{"baseUrl":"https://x"}""").notifyWhenDone)
	}
}
