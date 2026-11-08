package com.eva.feature_settings.screen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.drive.domain.auth.GoogleDriveAuth
import com.eva.drive.domain.models.DriveSettings
import com.eva.drive.domain.repository.DriveSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
internal class DriveSettingsViewModel @Inject constructor(
	private val settingsRepository: DriveSettingsRepository,
	val auth: GoogleDriveAuth,
) : ViewModel() {
	val settings = settingsRepository.settings.stateIn(
		scope = viewModelScope,
		started = SharingStarted.WhileSubscribed(5_000),
		initialValue = DriveSettings(),
	)
	private val _isBusy = MutableStateFlow(false)
	val isBusy = _isBusy.asStateFlow()
	private val _errorMessage = MutableSharedFlow<String>()
	val errorMessage = _errorMessage.asSharedFlow()
	private val _signInSuccess = MutableSharedFlow<Unit>()
	val signInSuccess = _signInSuccess.asSharedFlow()

	fun setAutoUploadEnabled(enabled: Boolean) = viewModelScope.launch {
		settingsRepository.setAutoUploadEnabled(enabled)
	}

	fun setWifiOnlyEnabled(enabled: Boolean) = viewModelScope.launch {
		settingsRepository.setWifiOnlyEnabled(enabled)
	}

	fun signIn(activityContext: Context) = runAuthOperation {
		auth.signIn(activityContext)
		auth.beginDriveAuthorization()
		_signInSuccess.emit(Unit)
	}

	fun completeAuthorization(resultData: Any?) = runAuthOperation {
		auth.completeDriveAuthorization(resultData).getOrThrow()
		_signInSuccess.emit(Unit)
	}

	fun signOut() = runAuthOperation {
		auth.signOut()
	}

	private fun runAuthOperation(block: suspend () -> Unit) = viewModelScope.launch {
		if (_isBusy.value) return@launch
		_isBusy.value = true
		try {
			block()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			_errorMessage.emit(e.message ?: "Google Drive authorization failed")
		} finally {
			_isBusy.value = false
		}
	}
}
