package io.github.richardhan.voicenote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.concurrent.TimeUnit

internal fun Context.lastRecordingLocation(): Location? {
	val manager = getSystemService(LocationManager::class.java) ?: return null
	return try {
		manager.getProviders(true).mapNotNull { provider ->
			try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
		}.filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..TimeUnit.MINUTES.toNanos(10) }
			.maxByOrNull { it.elapsedRealtimeNanos }
	} catch (_: SecurityException) { null }
}

@Suppress("DEPRECATION")
internal class CallPause(context: Context, onCall: () -> Unit) {
	private val phone = context.getSystemService(TelephonyManager::class.java)
	private val listener = object : PhoneStateListener() {
		override fun onCallStateChanged(state: Int, number: String?) {
			if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) onCall()
		}
	}

	fun listen() {
		try { phone?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
		catch (_: SecurityException) { }
	}
	fun close() { phone?.listen(listener, PhoneStateListener.LISTEN_NONE) }
}

@Composable
internal fun RecordingContextOptions(library: RecordingLibrary) {
	val context = LocalContext.current
	val phone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		library.contextOption("callPause", it)
	}
	val location = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		library.contextOption("location", it)
	}
	FlowRow {
		FilterChip(selected = library.callPause, label = { Text(stringResource(R.string.call_pause)) }, onClick = {
			if (library.callPause || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
				library.contextOption("callPause", !library.callPause)
			else phone.launch(Manifest.permission.READ_PHONE_STATE)
		})
		FilterChip(selected = library.tagLocation, label = { Text(stringResource(R.string.tag_location)) }, onClick = {
			if (library.tagLocation || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
				library.contextOption("location", !library.tagLocation)
			else location.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
		})
	}
}
