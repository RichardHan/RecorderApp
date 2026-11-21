package com.eva.cloudtranscribe.usage

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Reads the current battery level and persists start/end snapshots into `device-context.json`. */
@Singleton
class RecordingBatteryCapture @Inject constructor(
	@ApplicationContext private val context: Context,
	private val store: DeviceContextBatteryStore,
) : RecordingSessionCapture {

	override fun onRecordingStarted() {
		readLevel()?.let { store.savePendingStart(it) }
	}

	override fun onRecordingSaved(recordingId: Long) {
		val end = readLevel()
		val start = store.readPendingStart()
		val endedAt = end?.capturedAtMillis ?: System.currentTimeMillis()
		val capturedAt = start?.capturedAtMillis ?: endedAt
		store.finalizeRecording(recordingId, start, end, capturedAt, endedAt)
	}

	override fun onRecordingAbandoned() {
		store.clearPending()
	}

	fun readLevel(nowMillis: Long = System.currentTimeMillis()): BatteryLevelSnapshot? =
		readBattery(context, nowMillis)

	companion object {
		fun readBattery(context: Context, nowMillis: Long = System.currentTimeMillis()): BatteryLevelSnapshot? {
			val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
			return fromIntent(intent, nowMillis)
		}

		fun fromIntent(intent: Intent, nowMillis: Long): BatteryLevelSnapshot? {
			val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
			val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
			if (level < 0 || scale <= 0) return null
			val percent = ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
			val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
			val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
			return BatteryLevelSnapshot(levelPercent = percent, isCharging = charging, capturedAtMillis = nowMillis)
		}
	}
}
