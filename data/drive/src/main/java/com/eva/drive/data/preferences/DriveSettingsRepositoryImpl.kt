package com.eva.drive.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.eva.drive.domain.models.DriveSettings
import com.eva.drive.domain.repository.DriveSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.drivePreferences by preferencesDataStore(name = "google_drive_upload")

internal class DriveSettingsRepositoryImpl @Inject constructor(
	@ApplicationContext private val context: Context,
) : DriveSettingsRepository {

	override val settings: Flow<DriveSettings> = context.drivePreferences.data.map { preferences ->
		DriveSettings(
			accountEmail = preferences[ACCOUNT_EMAIL]?.takeIf(String::isNotBlank),
			autoUploadEnabled = preferences[AUTO_UPLOAD] ?: false,
			wifiOnly = preferences[WIFI_ONLY] ?: false,
			targetFolderId = preferences[TARGET_FOLDER_ID]?.takeIf(String::isNotBlank),
		)
	}

	override suspend fun currentSettings(): DriveSettings = settings.first()

	override suspend fun updateAccountAndFolder(accountEmail: String, targetFolderId: String?) {
		context.drivePreferences.edit { preferences ->
			val previousAccount = preferences[ACCOUNT_EMAIL]
			if (previousAccount != accountEmail) preferences.remove(TARGET_FOLDER_ID)
			preferences[ACCOUNT_EMAIL] = accountEmail
			targetFolderId?.let { preferences[TARGET_FOLDER_ID] = it }
		}
	}

	override suspend fun setAutoUploadEnabled(enabled: Boolean) {
		context.drivePreferences.edit { it[AUTO_UPLOAD] = enabled }
	}

	override suspend fun setWifiOnlyEnabled(enabled: Boolean) {
		context.drivePreferences.edit { it[WIFI_ONLY] = enabled }
	}

	override suspend fun setTargetFolderId(folderId: String) {
		context.drivePreferences.edit { it[TARGET_FOLDER_ID] = folderId }
	}

	override suspend fun signOut() {
		context.drivePreferences.edit { preferences ->
			preferences.remove(ACCOUNT_EMAIL)
			preferences.remove(TARGET_FOLDER_ID)
			preferences[AUTO_UPLOAD] = false
		}
	}

	private companion object {
		val ACCOUNT_EMAIL = stringPreferencesKey("account_email")
		val AUTO_UPLOAD = booleanPreferencesKey("auto_upload_enabled")
		val WIFI_ONLY = booleanPreferencesKey("wifi_only")
		val TARGET_FOLDER_ID = stringPreferencesKey("target_folder_id")
	}
}
