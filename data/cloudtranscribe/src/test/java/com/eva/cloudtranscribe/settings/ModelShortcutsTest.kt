package com.eva.cloudtranscribe.settings

import com.eva.cloudtranscribe.model.CloudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelShortcutsTest {

	@Test
	fun recordPrependsAndKeepsNewestFirst() {
		val after = ModelShortcuts.record(emptyList(), "gpt-4o-transcribe")
		assertEquals(listOf("gpt-4o-transcribe"), after)
		assertEquals(
			listOf("whisper-1", "gpt-4o-transcribe"),
			ModelShortcuts.record(after, "whisper-1"),
		)
	}

	@Test
	fun recordMovesExistingValueToFrontWithoutDuplicating() {
		val recent = listOf("a", "b", "c")
		assertEquals(listOf("b", "a", "c"), ModelShortcuts.record(recent, "b"))
		assertEquals(listOf("b", "a", "c"), ModelShortcuts.record(recent, "  b  "))
	}

	@Test
	fun recordCapsAtFiveDistinctValues() {
		var recent = emptyList<String>()
		listOf("m1", "m2", "m3", "m4", "m5", "m6").forEach { recent = ModelShortcuts.record(recent, it) }
		assertEquals(listOf("m6", "m5", "m4", "m3", "m2"), recent)
		assertEquals(5, recent.size)
	}

	@Test
	fun recordIgnoresBlankAndWhitespaceOnlyValues() {
		val recent = listOf("kept")
		assertEquals(recent, ModelShortcuts.record(recent, ""))
		assertEquals(recent, ModelShortcuts.record(recent, "   "))
		assertEquals(recent, ModelShortcuts.record(recent, "\t\n"))
	}

	@Test
	fun recordTreatsDifferentCaseAsDistinct() {
		assertEquals(
			listOf("GPT-4o", "gpt-4o"),
			ModelShortcuts.record(listOf("gpt-4o"), "GPT-4o"),
		)
	}

	@Test
	fun recordSanitizesExistingEntriesAndHonorsCustomLimit() {
		val messy = listOf("  one  ", "", "one", "two", "  ")
		assertEquals(listOf("three", "one", "two"), ModelShortcuts.record(messy, "three"))
		assertEquals(listOf("new"), ModelShortcuts.record(listOf("a", "b", "c"), "new", limit = 1))
		assertEquals(emptyList<String>(), ModelShortcuts.record(listOf("a"), "b", limit = 0))
	}

	@Test
	fun recordTruncatesOverlongValues() {
		val long = "m".repeat(ModelShortcuts.MAX_MODEL_LENGTH + 20)
		val recorded = ModelShortcuts.record(emptyList(), long)
		assertEquals(1, recorded.size)
		assertEquals(ModelShortcuts.MAX_MODEL_LENGTH, recorded.single().length)
	}

	@Test
	fun chipsShowRecentsFirstThenUnusedPresets() {
		val recents = listOf("custom-a", CloudSettings.DEFAULT_MODEL, "custom-b")
		val chips = ModelShortcuts.chips(recents, ModelShortcuts.PRESETS)
		assertEquals(
			listOf("custom-a", CloudSettings.DEFAULT_MODEL, "custom-b", "gemini-3.5-transcribe"),
			chips,
		)
		assertFalse(chips.drop(3).contains(CloudSettings.DEFAULT_MODEL))
		assertEquals(chips, chips.distinct())
	}

	@Test
	fun allFieldsShareTheSameTwoPresets() {
		assertEquals(
			listOf("gemini-3.8-flash", "gemini-3.5-transcribe"),
			ModelShortcuts.PRESETS,
		)
		assertEquals(CloudSettings.DEFAULT_MODEL, ModelShortcuts.PRESETS.first())
	}

	@Test
	fun chipsIgnoreBlankRecentsAndPresetDuplicates() {
		val chips = ModelShortcuts.chips(
			recent = listOf("  ", "keep", "keep"),
			presets = listOf("keep", "extra", " extra ", ""),
		)
		assertEquals(listOf("keep", "extra"), chips)
	}
}
