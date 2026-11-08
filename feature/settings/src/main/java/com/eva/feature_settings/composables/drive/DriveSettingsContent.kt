package com.eva.feature_settings.composables.drive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eva.drive.domain.models.DriveSettings
import com.eva.ui.R
import com.eva.feature_settings.composables.SettingsItemWithSwitch
import com.eva.feature_settings.screen.DriveSettingsViewModel
import com.eva.ui.utils.LocalSnackBarProvider
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

@Composable
internal fun DriveSettingsContent(
	modifier: Modifier = Modifier,
	viewModel: DriveSettingsViewModel = hiltViewModel(),
) {
	val settings by viewModel.settings.collectAsStateWithLifecycle()
	val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
	val snackbar = LocalSnackBarProvider.current
	val context = LocalContext.current

	val authorizationLauncher = rememberLauncherForActivityResult(
		contract = ActivityResultContracts.StartIntentSenderForResult(),
	) { result ->
		viewModel.completeAuthorization(result.data)
	}

	LaunchedEffect(viewModel) {
		viewModel.auth.authorizationPendingIntents.collect { pending ->
			authorizationLauncher.launch(
				androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build(),
			)
		}
	}
	LaunchedEffect(viewModel) {
		merge(viewModel.errorMessage, viewModel.signInSuccess.map {
			context.getString(R.string.drive_account_ready)
		}).collect { launch { snackbar.showSnackbar(it) } }
	}

	DriveSettingsContent(
		settings = settings,
		isBusy = isBusy,
		onAuthorize = { viewModel.signIn(context) },
		onSignIn = { viewModel.signIn(context) },
		onSignOut = viewModel::signOut,
		onAutoUploadChanged = viewModel::setAutoUploadEnabled,
		onWifiOnlyChanged = viewModel::setWifiOnlyEnabled,
		modifier = modifier,
	)
}


@Composable
private fun DriveSettingsContent(
	settings: DriveSettings,
	isBusy: Boolean,
	onAuthorize: () -> Unit,
	onSignIn: () -> Unit,
	onSignOut: () -> Unit,
	onAutoUploadChanged: (Boolean) -> Unit,
	onWifiOnlyChanged: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier = modifier.fillMaxSize(),
	) {
		item {
			Column(modifier = Modifier.padding(horizontal = 16.dp)) {
				Text(
					text = stringResource(R.string.drive_settings_description),
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(bottom = 12.dp),
				)
				if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
				ListItem(
					headlineContent = { Text(stringResource(R.string.drive_account_title)) },
					supportingContent = {
						Text(
							settings.accountEmail
								?: stringResource(R.string.drive_account_not_signed_in),
						)
					},
				)
				Button(
					onClick = if (settings.isSignedIn) onSignOut else onSignIn,
					enabled = !isBusy,
					modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
				) {
					Text(
						stringResource(if (settings.isSignedIn) R.string.drive_sign_out else R.string.drive_sign_in),
					)
				}
				if (settings.isSignedIn) {
					Button(
						onClick = onAuthorize,
						enabled = !isBusy,
						modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
					) {
						Text(stringResource(R.string.drive_renew_access))
					}
				}
			}
		}
		item {
			SettingsItemWithSwitch(
				isSelected = settings.autoUploadEnabled,
				enabled = settings.isSignedIn && !isBusy,
				title = stringResource(R.string.drive_auto_upload_title),
				text = stringResource(R.string.drive_auto_upload_description),
				onSelect = onAutoUploadChanged,
			)
		}
		item {
			SettingsItemWithSwitch(
				isSelected = settings.wifiOnly,
				enabled = !isBusy,
				title = stringResource(R.string.drive_wifi_only_title),
				text = stringResource(R.string.drive_wifi_only_description),
				onSelect = onWifiOnlyChanged,
			)
		}
		item {
			ListItem(
				headlineContent = { Text(stringResource(R.string.drive_folder_title)) },
				supportingContent = { Text(stringResource(R.string.drive_folder_description)) },
			)
		}
	}
}
