package com.eva.drive.domain.auth

import android.content.Context
import kotlinx.coroutines.flow.Flow

interface GoogleDriveAuth {
	val authorizationPendingIntents: Flow<DriveAuthorizationIntent>

	suspend fun signIn(activityContext: Context)

	suspend fun beginDriveAuthorization()

	suspend fun completeDriveAuthorization(resultData: Any?): Result<String>

	/** Returns a short-lived token for an account that previously granted drive.file. */
	suspend fun accessToken(): Result<String>

	/** Invalidates a token after HTTP 401 so the next authorization refreshes it. */
	suspend fun invalidateAccessToken(token: String)

	suspend fun signOut()
}

data class DriveAuthorizationIntent(val intentSender: android.content.IntentSender)

class DriveAuthorizationRequiredException : Exception(
	"Google Drive authorization needs to be renewed in Settings > Cloud upload",
)
