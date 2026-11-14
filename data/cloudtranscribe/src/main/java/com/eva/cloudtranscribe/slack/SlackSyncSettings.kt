package com.eva.cloudtranscribe.slack

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eva.cloudtranscribe.secure.EncryptedKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Non-secret Slack sync preferences. Everything is OFF until the user enables it and agrees in the consent dialog. */
@Serializable
data class SlackSyncSettings(
	val enabled: Boolean = false,
	/** When the user agreed; cleared whenever sync is turned off so re-enabling asks again. */
	val consentAtMillis: Long? = null,
	/** Shown in consent / recorder UI only; the app cannot read the channel name from a webhook. */
	val channelLabel: String = "",
	val sendStart: Boolean = true,
	val sendKeyPoints: Boolean = true,
	val sendEnd: Boolean = true,
	val minIntervalMinutes: Int = SlackThrottle.DEFAULT_INTERVAL_MINUTES,
	val maskSensitive: Boolean = false,
) {
	val isActive: Boolean get() = enabled && consentAtMillis != null
	val normalizedIntervalMinutes: Int
		get() = minIntervalMinutes.coerceIn(SlackThrottle.MIN_INTERVAL_MINUTES, SlackThrottle.MAX_INTERVAL_MINUTES)

	companion object {
		const val MAX_LABEL = 40
	}
}

private val Context.slackPrefs by preferencesDataStore(name = "slack_sync_settings")

@Singleton
class SlackSyncSettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val key = stringPreferencesKey("slack_sync_v1")

	private fun decode(raw: String?): SlackSyncSettings =
		raw?.let { runCatching { json.decodeFromString(SlackSyncSettings.serializer(), it) }.getOrNull() } ?: SlackSyncSettings()

	val settings: Flow<SlackSyncSettings> = context.slackPrefs.data.map { decode(it[key]) }

	suspend fun current(): SlackSyncSettings = settings.first()

	suspend fun update(transform: (SlackSyncSettings) -> SlackSyncSettings) {
		context.slackPrefs.edit { prefs ->
			val next = transform(decode(prefs[key])).let { s ->
				s.copy(
					channelLabel = s.channelLabel.trim().take(SlackSyncSettings.MAX_LABEL),
					// turning off always drops consent: re-enabling must ask again
					consentAtMillis = if (s.enabled) s.consentAtMillis else null,
				)
			}
			prefs[key] = json.encodeToString(SlackSyncSettings.serializer(), next)
		}
	}
}

/** Webhook URL (contains a secret): AES-GCM with an AndroidKeyStore key, excluded from backups, never logged. */
@Singleton
class SlackWebhookStore @Inject constructor(@ApplicationContext context: Context) :
	EncryptedKeyStore(context, PREFS, ALIAS) {
	companion object {
		const val PREFS = "slack_webhook_secure"
		const val ALIAS = "recorderapp_slack_webhook"
	}
}
