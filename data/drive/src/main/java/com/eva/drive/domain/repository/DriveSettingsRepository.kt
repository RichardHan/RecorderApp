package com.eva.drive.domain.repository

import com.eva.drive.domain.models.DriveSettings
import kotlinx.coroutines.flow.Flow

interface DriveSettingsRepository {
	val settings: Flow<DriveSettings>

	suspend fun currentSettings(): DriveSettings

	suspend fun updateAccountAndFolder(accountEmail: String, targetFolderId: String? = null)

	suspend fun setAutoUploadEnabled(enabled: Boolean)

	suspend fun setWifiOnlyEnabled(enabled: Boolean)

	suspend fun setTargetFolderId(folderId: String)

	suspend fun signOut()
}
