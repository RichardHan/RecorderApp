package com.eva.cloudtranscribe.usage

import com.eva.cloudtranscribe.device.BatterySnapshot
import com.eva.cloudtranscribe.device.RecordingDeviceContext
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceContextBatteryTests {
	@Test
	fun summarizeSavedDeviceSnapshots() {
		val start = BatterySnapshot(80, capturedAtMillis = 100)
		val session = RecordingDeviceContext(batteryStart = start, batteryEnd = start.copy(levelPercent = 73))
		val contexts = listOf(session, session.copy(batteryEnd = null),
			session.copy(batteryEnd = start.copy(levelPercent = 105)),
			session.copy(batteryEnd = start.copy(levelPercent = 95, isCharging = true)))
		assertEquals(BatteryUsageSummary(1, 7, 1), summarizeBatteryUsage(contexts))
	}
}
