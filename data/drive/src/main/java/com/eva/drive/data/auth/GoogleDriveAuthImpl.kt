package com.eva.drive.data.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.eva.drive.BuildConfig
import com.eva.drive.domain.auth.DriveAuthorizationIntent
import com.eva.drive.domain.auth.DriveAuthorizationRequiredException
import com.eva.drive.domain.auth.GoogleDriveAuth
import com.eva.drive.domain.repository.DriveSettingsRepository
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.api.services.drive.DriveScopes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

internal class GoogleDriveAuthImpl @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settingsRepository: DriveSettingsRepository,
) : GoogleDriveAuth {
	private val credentialManager = CredentialManager.create(context)
	private val authorizationClient = Identity.getAuthorizationClient(context)
	private val _authorizationPendingIntents = MutableSharedFlow<DriveAuthorizationIntent>(
		extraBufferCapacity = 1,
	)
	override val authorizationPendingIntents = _authorizationPendingIntents.asSharedFlow()

	override suspend fun signIn(activityContext: Context) {
		require(BuildConfig.DRIVE_WEB_CLIENT_ID.isNotBlank()) {
			"DRIVE_WEB_CLIENT_ID is missing. See docs/DRIVE_SETUP.md."
		}
		val googleIdOption = GetGoogleIdOption.Builder()
			.setServerClientId(BuildConfig.DRIVE_WEB_CLIENT_ID)
			.setFilterByAuthorizedAccounts(false)
			.setAutoSelectEnabled(false)
			.build()
		val request = GetCredentialRequest.Builder()
			.addCredentialOption(googleIdOption)
			.build()
		val response = credentialManager.getCredential(activityContext, request)
		val credential = response.credential as? CustomCredential
			?: throw IllegalStateException("No Google account credential returned")
		require(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
			"Unexpected credential type: ${credential.type}"
		}
		val googleAccount = GoogleIdTokenCredential.createFrom(credential.data)
		val email = googleAccount.id
		require(email.contains('@')) { "Google account credential did not contain an email" }
		settingsRepository.updateAccountAndFolder(email)
	}

	override suspend fun beginDriveAuthorization() {
		val result = withContext(Dispatchers.IO) {
			Tasks.await(authorizationClient.authorize(authorizationRequest()))
		}
		val token = result.accessToken
		if (!token.isNullOrBlank()) return
		val pendingIntent = result.pendingIntent
		if (result.hasResolution() && pendingIntent != null) {
			_authorizationPendingIntents.emit(DriveAuthorizationIntent(pendingIntent.intentSender))
		} else throw DriveAuthorizationRequiredException()
	}

	override suspend fun completeDriveAuthorization(resultData: Any?): Result<String> {
		val intent = resultData as? Intent
			?: return Result.failure(IllegalArgumentException("Missing Google authorization result"))
		return try {
			withContext(Dispatchers.IO) {
				val result = authorizationClient.getAuthorizationResultFromIntent(intent)
				val token = result.accessToken
				if (token.isNullOrBlank()) Result.failure(DriveAuthorizationRequiredException())
				else Result.success(token)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Result.failure(e)
		}
	}

	override suspend fun accessToken(): Result<String> {
		if (!settingsRepository.currentSettings().isSignedIn) return Result.failure(
			DriveAuthorizationRequiredException(),
		)
		return try {
			withContext(Dispatchers.IO) {
				val result = Tasks.await(authorizationClient.authorize(authorizationRequest()))
				val token = result.accessToken
				if (result.hasResolution() || token.isNullOrBlank()) {
					Result.failure(DriveAuthorizationRequiredException())
				} else Result.success(token)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Result.failure(e)
		}
	}

	override suspend fun invalidateAccessToken(token: String) {
		withContext(Dispatchers.IO) {
			Tasks.await(authorizationClient.clearToken(ClearTokenRequest.builder().setToken(token).build()))
		}
	}

	override suspend fun signOut() {
		credentialManager.clearCredentialState(ClearCredentialStateRequest())
		settingsRepository.signOut()
	}

	private suspend fun authorizationRequest(): AuthorizationRequest {
		val settings = settingsRepository.currentSettings()
		return AuthorizationRequest.builder()
			.setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
			.apply {
				settings.accountEmail?.let { setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) }
			}
			.build()
	}

	private companion object {
		const val GOOGLE_ACCOUNT_TYPE = "com.google"
	}
}
