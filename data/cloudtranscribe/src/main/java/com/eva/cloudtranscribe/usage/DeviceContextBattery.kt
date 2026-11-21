package com.eva.cloudtranscribe.usage

import com.eva.cloudtranscribe.device.RecordingDeviceContext

data class BatteryUsageSummary(
	val recordingsWithData: Int = 0,
	val drainPercent: Int = 0,
	val chargingSessions: Int = 0,
) {
	val hasData: Boolean get() = recordingsWithData > 0
}

fun summarizeBatteryUsage(contexts: List<RecordingDeviceContext>): BatteryUsageSummary {
	var withData = 0
	var drain = 0
	var charging = 0
	contexts.forEach { ctx ->
		val start = ctx.batteryStart?.levelPercent?.takeIf { it in 0..100 } ?: return@forEach
		val end = ctx.batteryEnd?.levelPercent?.takeIf { it in 0..100 } ?: return@forEach
		val used = (start - end).coerceAtLeast(0)
		val wasCharging = ctx.batteryStart.isCharging || ctx.batteryEnd.isCharging
		if (wasCharging && used == 0) {
			charging += 1
			return@forEach
		}
		withData += 1
		drain += used
		if (wasCharging) charging += 1
	}
	return BatteryUsageSummary(recordingsWithData = withData, drainPercent = drain, chargingSessions = charging)
}
