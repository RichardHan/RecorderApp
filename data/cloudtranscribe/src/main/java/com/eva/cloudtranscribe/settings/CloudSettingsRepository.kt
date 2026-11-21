package com.eva.cloudtranscribe.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.CustomVocabulary
import com.eva.cloudtranscribe.usage.TokenPricing
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.cloudAiPrefs by preferencesDataStore(name = "cloud_ai_settings")

/** Non-secret settings only (endpoint, models, chunking). */
@Singleton
class CloudSettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val key = stringPreferencesKey("settings_v1")
	private val recentTranscribeKey = stringPreferencesKey("recent_transcribe_models_v1")
	private val recentSummaryKey = stringPreferencesKey("recent_summary_models_v1")
	private val stringList = ListSerializer(String.serializer())

	private fun decode(raw: String?): CloudSettings =
		raw?.let { runCatching { json.decodeFromString(CloudSettings.serializer(), it) }.getOrNull() } ?: CloudSettings()

	/** Trims the free-text format instructions so a pasted block cannot grow without bound. */
	private fun CloudSettings.normalized() = copy(
		customSummaryInstructions = customSummaryInstructions.trim().take(CloudSettings.MAX_SUMMARY_INSTRUCTIONS),
		customVocabulary = CustomVocabulary.normalize(customVocabulary),
		modelPrices = TokenPricing.normalizePrices(modelPrices),
	)

	val settings: Flow<CloudSettings> = context.cloudAiPrefs.data.map { prefs -> decode(prefs[key]) }
	val recentTranscribeModels: Flow<List<String>> = context.cloudAiPrefs.data.map { decodeRecent(it[recentTranscribeKey]) }
	val recentSummaryModels: Flow<List<String>> = context.cloudAiPrefs.data.map { decodeRecent(it[recentSummaryKey]) }

	suspend fun current(): CloudSettings = settings.first()

	suspend fun update(transform: (CloudSettings) -> CloudSettings) {
		context.cloudAiPrefs.edit { prefs ->
			prefs[key] = json.encodeToString(CloudSettings.serializer(), transform(decode(prefs[key])).normalized())
		}
	}

	/** Records a successfully saved transcription-model value (newest first, max 5). */
	suspend fun recordRecentTranscribe(value: String) = recordRecent(recentTranscribeKey, value)

	/** Records a successfully saved summary-model value (newest first, max 5). Shared with live-cloud summary. */
	suspend fun recordRecentSummary(value: String) = recordRecent(recentSummaryKey, value)

	private suspend fun recordRecent(storeKey: Preferences.Key<String>, value: String) {
		context.cloudAiPrefs.edit { prefs ->
			prefs[storeKey] = json.encodeToString(stringList, ModelShortcuts.record(decodeRecent(prefs[storeKey]), value))
		}
	}

	private fun decodeRecent(raw: String?): List<String> =
		ModelShortcuts.sanitize(raw?.let { runCatching { json.decodeFromString(stringList, it) }.getOrNull() } ?: emptyList())
}
