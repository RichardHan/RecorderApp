package com.eva.cloudtranscribe.live

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.liveCloudPrefs by preferencesDataStore(name = "live_cloud_settings")

/** Persists [LiveCloudSettings] (incl. the editable summary prompt). No secrets here. */
@Singleton
class LiveCloudSettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val key = stringPreferencesKey("live_cloud_v1")

	val settings: Flow<LiveCloudSettings> = context.liveCloudPrefs.data.map { decode(it[key]) }

	suspend fun current(): LiveCloudSettings = settings.first()

	suspend fun update(transform: (LiveCloudSettings) -> LiveCloudSettings) {
		context.liveCloudPrefs.edit { prefs -> prefs[key] = json.encodeToString(LiveCloudSettings.serializer(), transform(decode(prefs[key])).normalized()) }
	}

	private fun decode(raw: String?): LiveCloudSettings =
		raw?.let { runCatching { json.decodeFromString(LiveCloudSettings.serializer(), it).normalized() }.getOrNull() } ?: LiveCloudSettings()
}
