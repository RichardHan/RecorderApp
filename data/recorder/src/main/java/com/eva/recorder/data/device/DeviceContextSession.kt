package com.eva.recorder.data.device

import android.util.Log
import com.eva.cloudtranscribe.device.BatterySnapshot
import com.eva.cloudtranscribe.device.DeviceContextSettingsRepository
import com.eva.cloudtranscribe.device.DeviceLocationSnapshot
import com.eva.cloudtranscribe.device.NetworkSnapshot
import com.eva.cloudtranscribe.device.RecordingDeviceContext
import com.eva.cloudtranscribe.device.StorageSnapshot
import com.eva.cloudtranscribe.store.TranscriptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DEVICE_CTX_SESSION"
private const val FINISH_LOCATION_GRACE_MS = 1_500L

/**
 * Captures a one-shot [RecordingDeviceContext] when recording starts (never blocking audio capture)
 * and finalizes battery/storage end readings when the recording is saved.
 */
internal class DeviceContextSession(
	private val collector: DeviceContextCollector,
	private val settingsRepo: DeviceContextSettingsRepository,
	private val transcriptStore: TranscriptStore,
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
	private val clock: () -> Long = { System.currentTimeMillis() },
) {
	private class ActiveCapture(
		val startedAtMillis: Long,
		val batteryStart: BatterySnapshot?,
		val networkStart: NetworkSnapshot?,
		val storageDeferred: Deferred<StorageSnapshot?>,
		val locationDeferred: Deferred<DeviceLocationSnapshot?>?,
	) {
		fun cancel() {
			storageDeferred.cancel()
			locationDeferred?.cancel()
		}
	}

	private val lock = Any()
	private var active: ActiveCapture? = null

	suspend fun beginSession() {
		val settings = try {
			settingsRepo.current()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Failed to read device context settings; skipping", e)
			return
		}
		if (!settings.enabled) {
			cancelSession()
			return
		}

		val now = clock()
		val batteryStart = runCatching { collector.captureBattery() }.getOrNull()
		val networkStart = runCatching { collector.captureNetwork() }.getOrNull()
		val storageDeferred = scope.async { runCatching { collector.captureStorage() }.getOrNull() }
		val locationDeferred = if (settings.includeLocation) {
			scope.async { runCatching { collector.captureLocation() }.getOrNull() }
		} else null

		val next = ActiveCapture(
			startedAtMillis = now,
			batteryStart = batteryStart,
			networkStart = networkStart,
			storageDeferred = storageDeferred,
			locationDeferred = locationDeferred,
		)
		val previous = synchronized(lock) {
			val prev = active
			active = next
			prev
		}
		previous?.cancel()
	}

	fun cancelSession() {
		val prev = synchronized(lock) {
			val p = active
			active = null
			p
		}
		prev?.cancel()
	}

	suspend fun finishSession(recordingId: Long) {
		val capture = synchronized(lock) {
			val c = active
			active = null
			c
		} ?: return

		val endedAt = clock()
		val batteryEnd = runCatching { collector.captureBattery() }.getOrNull()
		val storageEnd = runCatching { collector.captureStorage() }.getOrNull()
		val storageStart = withTimeoutOrNull(1_000L) { capture.storageDeferred.await() }
			?: run {
				capture.storageDeferred.cancel()
				null
			}
		val location = capture.locationDeferred?.let { deferred ->
			withTimeoutOrNull(FINISH_LOCATION_GRACE_MS) { deferred.await() }
				?: run {
					deferred.cancel()
					null
				}
		}

		val context = RecordingDeviceContext(
			recordingId = recordingId,
			capturedAtMillis = capture.startedAtMillis,
			endedAtMillis = endedAt,
			location = location,
			batteryStart = capture.batteryStart,
			batteryEnd = batteryEnd,
			networkStart = capture.networkStart,
			storageStart = storageStart,
			storageEnd = storageEnd,
		)

		withContext(Dispatchers.IO) {
			try {
				transcriptStore.saveDeviceContext(recordingId, context)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.w(TAG, "Failed to save device context for $recordingId", e)
			}
		}
	}
}
