package com.eva.cloudtranscribe.device

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

private val Context.deviceContextPrefs by preferencesDataStore(name = "device_context_settings")

@Singleton
class DeviceContextSettingsRepository @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val key = stringPreferencesKey("device_context_v1")

	val settings: Flow<DeviceContextSettings> = context.deviceContextPrefs.data.map { decode(it[key]) }

	suspend fun current(): DeviceContextSettings = settings.first()

	suspend fun update(transform: (DeviceContextSettings) -> DeviceContextSettings) {
		context.deviceContextPrefs.edit { prefs ->
			prefs[key] = json.encodeToString(DeviceContextSettings.serializer(), transform(decode(prefs[key])))
		}
	}

	private fun decode(raw: String?): DeviceContextSettings =
		raw?.let { runCatching { json.decodeFromString(DeviceContextSettings.serializer(), it) }.getOrNull() }
			?: DeviceContextSettings()
}
