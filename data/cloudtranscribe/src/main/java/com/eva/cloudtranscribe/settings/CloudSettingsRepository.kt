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

	val settings: Flow<CloudSettings> = context.cloudAiPrefs.data.map { prefs ->
		prefs[key]?.let { runCatching { json.decodeFromString(CloudSettings.serializer(), it) }.getOrNull() } ?: CloudSettings()
	}

	suspend fun current(): CloudSettings = settings.first()

	suspend fun update(transform: (CloudSettings) -> CloudSettings) {
		context.cloudAiPrefs.edit { prefs ->
			val now = prefs[key]?.let { runCatching { json.decodeFromString(CloudSettings.serializer(), it) }.getOrNull() } ?: CloudSettings()
			prefs[key] = json.encodeToString(CloudSettings.serializer(), transform(now))
		}
	}
}
