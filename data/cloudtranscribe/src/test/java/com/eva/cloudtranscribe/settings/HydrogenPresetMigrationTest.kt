package com.eva.cloudtranscribe.settings

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HydrogenPresetMigrationTest {
	private val old = stringPreferencesKey("settings_v1")
	private val current = stringPreferencesKey("settings_v2")

	@Test fun switchesOnlyTheOldQualityModelAndDoesNotRepeat() = runTest {
		val source = Json.parseToJsonElement("""{"transcribeModel":"gemini-4-argon","providerType":"OPENAI_COMPATIBLE","summaryModel":"gemini-4-argon","workers":4}""").jsonObject
		val migrated = HydrogenPresetMigration.migrate(preferencesOf(old to source.toString()))
		assertEquals(JsonObject(source + ("transcribeModel" to JsonPrimitive("gemini-4-hydrogen"))), Json.parseToJsonElement(migrated[current]!!))
		assertFalse(HydrogenPresetMigration.shouldMigrate(migrated))
	}

	@Test fun keepsCustomModelsNativeGeminiAndSpeakerMode() = runTest {
		for (saved in listOf(
			"""{"transcribeModel":"my-gateway-model"}""",
			"""{"transcribeModel":"gemini-4-argon","providerType":"GEMINI_NATIVE"}""",
			"""{"transcribeModel":"gemini-3.5-transcribe","speakerDiarization":true}""",
			"""{"transcribeModel":"gemini-3.8-flash"}""",
		)) {
			assertEquals(saved, HydrogenPresetMigration.migrate(preferencesOf(old to saved))[current])
		}
	}
}
