package com.eva.cloudtranscribe.title

import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecordingNameTests {

	private val at = LocalDateTime(2026, 11, 20, 15, 30, 0)

	@Test
	fun date_time_prefix_is_sortable_and_uses_recording_clock() {
		assertEquals("2026-11-20 1530", AutoRecordingName.dateTimePrefix(at))
		assertEquals("2026-01-05 0905", AutoRecordingName.dateTimePrefix(LocalDateTime(2026, 1, 5, 9, 5)))
	}

	@Test
	fun format_keeps_prefix_title_and_extension() {
		assertEquals("2026-11-20 1530 週會討論.m4a", AutoRecordingName.format(at, "週會討論", ".m4a"))
		assertEquals("2026-11-20 1530 週會討論", AutoRecordingName.format(at, "週會討論", ""))
	}

	@Test
	fun format_sanitizes_and_rejects_empty_titles() {
		assertEquals("2026-11-20 1530 報價 v2.m4a", AutoRecordingName.format(at, "報價 / v2", ".m4a"))
		assertNull(AutoRecordingName.format(at, "好", ".m4a"))
		assertNull(AutoRecordingName.format(at, "  ", ".m4a"))
	}

	@Test
	fun format_cuts_the_base_to_the_length_limit() {
		val name = AutoRecordingName.format(at, "字".repeat(80), ".m4a")!!
		assertTrue(name.endsWith(".m4a"))
		assertTrue(name.removeSuffix(".m4a").length <= AutoRecordingName.MAX_BASE_CHARS)
		assertTrue(name.startsWith("2026-11-20 1530 "))
	}

	@Test
	fun original_auto_names_are_detected_with_or_without_extension() {
		assertTrue(AutoRecordingName.isOriginalAutoName("Voice-20 Nov 15:30"))
		assertTrue(AutoRecordingName.isOriginalAutoName("Voice-20 Nov 15:30.m4a"))
		assertTrue(AutoRecordingName.isOriginalAutoName("會議-05 Jan 9:05.m4a"))
		assertTrue(AutoRecordingName.isOriginalAutoName("Voice-20 Nov 15:30 (2).m4a"))
		assertTrue(AutoRecordingName.isOriginalAutoName("Voice-001"))
		assertTrue(AutoRecordingName.isOriginalAutoName("Voice-001.m4a"))
		assertTrue(AutoRecordingName.isOriginalAutoName("Voice-001 (3).m4a"))
	}

	@Test
	fun manual_or_ai_names_are_not_treated_as_original() {
		assertFalse(AutoRecordingName.isOriginalAutoName("2026-11-20 1530 週會討論.m4a"))
		assertFalse(AutoRecordingName.isOriginalAutoName("週會討論"))
		assertFalse(AutoRecordingName.isOriginalAutoName("週會討論.m4a"))
		assertFalse(AutoRecordingName.isOriginalAutoName("Voice 20 Nov 15:30"))
		assertFalse(AutoRecordingName.isOriginalAutoName(""))
	}

	@Test
	fun collision_suffix_and_extension_helpers() {
		assertEquals("2026-11-20 1530 週會討論.m4a", AutoRecordingName.withCollisionSuffix("2026-11-20 1530 週會討論.m4a", 1))
		assertEquals("2026-11-20 1530 週會討論 (2).m4a", AutoRecordingName.withCollisionSuffix("2026-11-20 1530 週會討論.m4a", 2))
		assertEquals("2026-11-20 1530 週會討論 (3)", AutoRecordingName.withCollisionSuffix("2026-11-20 1530 週會討論", 3))
		assertEquals(".m4a", AutoRecordingName.extensionOf("Voice-20 Nov 15:30.m4a"))
		assertEquals("", AutoRecordingName.extensionOf("Voice-20 Nov 15:30"))
		assertEquals("Voice-20 Nov 15:30", AutoRecordingName.titleFromDisplayName("Voice-20 Nov 15:30.m4a"))
	}
}
