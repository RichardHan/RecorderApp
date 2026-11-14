package com.eva.cloudtranscribe.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eva.cloudtranscribe.model.CloudSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.cloudAiPrefs by preferencesDataStore(name = "cloud_ai_settings")

/** Non-secret settings only (endpoint, models, chunking). */
@Singleton
class CloudSettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val key = stringPreferencesKey("settings_v1")

	private fun decode(raw: String?): CloudSettings =
		raw?.let { runCatching { json.decodeFromString(CloudSettings.serializer(), it) }.getOrNull() } ?: CloudSettings()

	/** Trims the free-text format instructions so a pasted block cannot grow without bound. */
	private fun CloudSettings.normalized() = copy(
		customSummaryInstructions = customSummaryInstructions.trim().take(CloudSettings.MAX_SUMMARY_INSTRUCTIONS),
	)

	val settings: Flow<CloudSettings> = context.cloudAiPrefs.data.map { prefs -> decode(prefs[key]) }

	suspend fun current(): CloudSettings = settings.first()

	suspend fun update(transform: (CloudSettings) -> CloudSettings) {
		context.cloudAiPrefs.edit { prefs ->
			prefs[key] = json.encodeToString(CloudSettings.serializer(), transform(decode(prefs[key])).normalized())
		}
	}
}
