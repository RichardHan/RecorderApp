package com.eva.cloudtranscribe.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceContextBatteryTests {
	@get:Rule val tmp = TemporaryFolder()

	private val start = BatteryLevelSnapshot(80, isCharging = false, capturedAtMillis = 1_000)
	private val end = BatteryLevelSnapshot(73, isCharging = false, capturedAtMillis = 2_000)

	@Test
	fun drainPercent_isStartMinusEndAndNeverNegative() {
		assertEquals(7, RecordingBatteryContext(batteryStart = start, batteryEnd = end).drainPercent)
		assertEquals(0, RecordingBatteryContext(batteryStart = start, batteryEnd = start.copy(levelPercent = 90)).drainPercent)
		assertNull(RecordingBatteryContext(batteryStart = start).drainPercent)
		assertNull(RecordingBatteryContext(batteryStart = start.copy(levelPercent = 200), batteryEnd = end).drainPercent)
	}

	@Test
	fun merge_preservesUnknownKeysAndUpdatesBattery() {
		val existing = """{"recordingId":9,"location":{"address":"台北市"},"networkStart":{"transport":"WIFI"},"batteryStart":{"levelPercent":99,"isCharging":false,"capturedAtMillis":1}}"""
		val merged = DeviceContextBatteryStore.mergeBatteryJson(existing, 42, start, end, 1_000, 2_000)
		assertTrue(merged.contains("台北市"))
		assertTrue(merged.contains("WIFI"))
		assertTrue(merged.contains("\"recordingId\":42"))
		assertTrue(merged.contains("\"levelPercent\":80"))
		assertTrue(merged.contains("\"levelPercent\":73"))
		val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
			.decodeFromString(RecordingBatteryContext.serializer(), merged)
		assertEquals(42L, parsed.recordingId)
		assertEquals(80, parsed.batteryStart!!.levelPercent)
		assertEquals(73, parsed.batteryEnd!!.levelPercent)
		assertEquals(2_000L, parsed.endedAtMillis)
	}

	@Test
	fun store_roundTripAndSummarizeIgnoresChargingWithNoDrain() {
		val store = DeviceContextBatteryStore(tmp.root)
		store.savePendingStart(start)
		assertEquals(80, store.readPendingStart()!!.levelPercent)
		store.finalizeRecording(11L, start, end, 1_000, 2_000)
		assertNull(store.readPendingStart())
		assertEquals(7, store.readContext(11L)!!.drainPercent)

		store.finalizeRecording(
			12L,
			BatteryLevelSnapshot(50, isCharging = true, capturedAtMillis = 3_000),
			BatteryLevelSnapshot(60, isCharging = true, capturedAtMillis = 4_000),
			3_000,
			4_000,
		)
		val summary = store.summarize()
		assertEquals(1, summary.recordingsWithData)
		assertEquals(7, summary.drainPercent)
		assertEquals(1, summary.chargingSessions)
	}

	@Test
	fun summarizeForPeriod_filtersByEndTime() {
		val contexts = listOf(
			RecordingBatteryContext(1, capturedAtMillis = 100, endedAtMillis = 150, batteryStart = start, batteryEnd = end),
			RecordingBatteryContext(2, capturedAtMillis = 500, endedAtMillis = 800, batteryStart = start, batteryEnd = end),
		)
		val inWindow = DeviceContextBatteryStore.summarizeForPeriod(contexts, 100, 200)
		assertEquals(1, inWindow.recordingsWithData)
		assertEquals(7, inWindow.drainPercent)
	}
}
