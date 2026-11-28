package io.github.richardhan.voicenote

import android.app.Activity
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun driveAccount(prefs: SharedPreferences): String? {
	var account by remember { mutableStateOf(prefs.getString("account", null)) }
	DisposableEffect(prefs) {
		val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "account") account = prefs.getString("account", null) }
		prefs.registerOnSharedPreferenceChangeListener(listener)
		onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
	}
	return account
}

@Composable
internal fun DrivePanel() {
	val context = LocalContext.current
	val prefs = context.drivePrefs()
	val account = driveAccount(prefs)
	val scope = rememberCoroutineScope()
	var failed by remember { mutableStateOf(false) }
	var busy by remember { mutableStateOf(false) }
	val client = remember(context) { Identity.getAuthorizationClient(context) }
	fun finish(token: String?) {
		scope.launch {
			try {
				val email = withContext(Dispatchers.IO) { driveEmail(requireNotNull(token)) }
				prefs.edit().putString("account", email).apply()
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { failed = true }
			finally { busy = false }
		}
	}
	val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) try { finish(client.getAuthorizationResultFromIntent(result.data).accessToken) }
		catch (_: Exception) { failed = true; busy = false } else busy = false
	}
	if (account == null) TextButton(enabled = !busy, onClick = {
		busy = true
		failed = false
		client.authorize(driveRequest()).addOnSuccessListener { result ->
			val pending = result.pendingIntent
			if (result.hasResolution() && pending != null) consent.launch(IntentSenderRequest.Builder(pending).build()) else finish(result.accessToken)
		}.addOnFailureListener { failed = true; busy = false }
	}) { Text(stringResource(R.string.connect_drive)) }
	else {
		Text("Google Drive · $account")
		var auto by remember(account) { mutableStateOf(prefs.getBoolean("auto", false)) }
		var wifi by remember(account) { mutableStateOf(prefs.getBoolean("wifi", false)) }
		FlowRow {
			FilterChip(selected = auto, onClick = { auto = !auto; prefs.edit().putBoolean("auto", auto).apply() }, label = { Text(stringResource(R.string.drive_auto)) })
			FilterChip(selected = wifi, onClick = { wifi = !wifi; prefs.edit().putBoolean("wifi", wifi).apply() }, label = { Text(stringResource(R.string.wifi_only)) })
			TextButton(onClick = {
				prefs.edit().remove("account").remove("auto").apply()
				WorkManager.getInstance(context).cancelAllWorkByTag("drive-backup")
			}) { Text(stringResource(R.string.disconnect_drive)) }
		}
	}
	if (failed) Text(stringResource(R.string.drive_auth_error))
}

@Composable
internal fun DriveAction(library: RecordingLibrary, file: File) {
	val context = LocalContext.current
	if (file.extension !in audioTypes || driveAccount(context.drivePrefs()) == null) return
	val status = library.catalog.value(file, "drive")
	TextButton(onClick = { library.catalog.update(file, "drive" to "pending"); context.queueDrive(file, false) }) {
		Text(stringResource(when (status) {
			"pending" -> R.string.drive_pending
			"done" -> R.string.drive_done
			"failed" -> R.string.drive_retry
			"auth" -> R.string.drive_auth_error
			else -> R.string.drive_upload
		}))
	}
}
