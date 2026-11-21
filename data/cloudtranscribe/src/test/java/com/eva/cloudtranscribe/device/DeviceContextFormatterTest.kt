package com.eva.cloudtranscribe.device

import com.eva.cloudtranscribe.store.TranscriptStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceContextFormatterTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

	@Test
	fun `batteryDeltaPercent calculates drain, charge, zero, and handles missing or invalid levels`() {
		val start = BatterySnapshot(levelPercent = 82, capturedAtMillis = 1_000L)
		val drained = BatterySnapshot(levelPercent = 77, capturedAtMillis = 2_000L)
		val charged = BatterySnapshot(levelPercent = 90, isCharging = true, capturedAtMillis = 2_000L)
		val same = BatterySnapshot(levelPercent = 82, capturedAtMillis = 2_000L)
		val invalid = BatterySnapshot(levelPercent = -1, capturedAtMillis = 2_000L)

		assertEquals(-5, DeviceContextFormatter.batteryDeltaPercent(start, drained))
		assertEquals(8, DeviceContextFormatter.batteryDeltaPercent(start, charged))
		assertEquals(0, DeviceContextFormatter.batteryDeltaPercent(start, same))
		assertNull(DeviceContextFormatter.batteryDeltaPercent(start, invalid))
		assertNull(DeviceContextFormatter.batteryDeltaPercent(invalid, drained))
		assertNull(DeviceContextFormatter.batteryDeltaPercent(null, drained))
		assertNull(DeviceContextFormatter.batteryDeltaPercent(start, null))
	}

	@Test
	fun `sanitizeWifiSsid strips quotes and rejects unknown placeholders`() {
		assertEquals("Office-5G", DeviceContextFormatter.sanitizeWifiSsid("\"Office-5G\""))
		assertEquals("CafeWiFi", DeviceContextFormatter.sanitizeWifiSsid(" CafeWiFi "))
		assertNull(DeviceContextFormatter.sanitizeWifiSsid("<unknown ssid>"))
		assertNull(DeviceContextFormatter.sanitizeWifiSsid("\"<unknown ssid>\""))
		assertNull(DeviceContextFormatter.sanitizeWifiSsid("0x"))
		assertNull(DeviceContextFormatter.sanitizeWifiSsid("   "))
		assertNull(DeviceContextFormatter.sanitizeWifiSsid(null))
	}

	@Test
	fun `cellularGenerationFromNetworkType maps standard network generations`() {
		assertEquals("5G", DeviceContextFormatter.cellularGenerationFromNetworkType(20))
		assertEquals("4G LTE", DeviceContextFormatter.cellularGenerationFromNetworkType(13))
		assertEquals("4G LTE", DeviceContextFormatter.cellularGenerationFromNetworkType(19))
		assertEquals("3G", DeviceContextFormatter.cellularGenerationFromNetworkType(15))
		assertEquals("2G", DeviceContextFormatter.cellularGenerationFromNetworkType(2))
		assertNull(DeviceContextFormatter.cellularGenerationFromNetworkType(0))
	}

	@Test
	fun `formatLocationSummary handles full address and coordinates-only fallback`() {
		val withAddress = DeviceLocationSnapshot(
			latitude = 25.03396,
			longitude = 121.56447,
			accuracyMeters = 14.6f,
			address = "台北市信義區市府路 1 號",
			capturedAtMillis = 1_000L,
			isPrecise = true,
		)
		val withAddressText = DeviceContextFormatter.formatLocationSummary(withAddress)!!
		assertTrue(withAddressText.contains("台北市信義區市府路 1 號"))
		assertTrue(withAddressText.contains("25.03396, 121.56447"))
		assertTrue(withAddressText.contains("±15 m"))

		val coordsOnly = withAddress.copy(address = null, accuracyMeters = null)
		assertEquals("25.03396, 121.56447", DeviceContextFormatter.formatLocationSummary(coordsOnly))
		assertNull(DeviceContextFormatter.formatLocationSummary(null))
	}

	@Test
	fun `formatBatterySummary shows start, end, delta, charging, temperature, and power save`() {
		val start = BatterySnapshot(
			levelPercent = 64,
			isCharging = true,
			pluggedType = BatteryPluggedType.USB,
			temperatureCelsius = 32.5f,
			isPowerSaveMode = true,
			capturedAtMillis = 1_000L,
		)
		val end = BatterySnapshot(levelPercent = 68, isCharging = true, capturedAtMillis = 2_000L)
		val text = DeviceContextFormatter.formatBatterySummary(start, end)!!
		assertTrue(text.contains("64% → 68% (+4%)"))
		assertTrue(text.contains("充電中"))
		assertTrue(text.contains("USB"))
		assertTrue(text.contains("32.5°C"))
		assertTrue(text.contains("省電模式"))
	}

	@Test
	fun `formatNetworkSummary includes ssid, cellular generation, vpn, metered, airplane`() {
		val wifi = NetworkSnapshot(
			transport = NetworkTransportType.WIFI,
			isConnected = true,
			wifiSsid = "\"HomeNet\"",
			isVpnActive = true,
			isMetered = false,
			isAirplaneMode = false,
			capturedAtMillis = 1_000L,
		)
		assertEquals("Wi-Fi (HomeNet)（VPN）", DeviceContextFormatter.formatNetworkSummary(wifi))

		val cell = NetworkSnapshot(
			transport = NetworkTransportType.CELLULAR,
			isConnected = true,
			cellularGeneration = "5G",
			isMetered = true,
			isVpnActive = false,
			isAirplaneMode = false,
			capturedAtMillis = 1_000L,
		)
		assertEquals("行動網路 (5G)（計量網路）", DeviceContextFormatter.formatNetworkSummary(cell))
	}

	@Test
	fun `formatStorageSummary and formatBytes format gigabytes and megabytes`() {
		assertEquals("512 MB", DeviceContextFormatter.formatBytes(512L * 1024L * 1024L))
		assertEquals("128.0 GB", DeviceContextFormatter.formatBytes(128L * 1024L * 1024L * 1024L))

		val start = StorageSnapshot(
			freeBytes = 40L * 1024L * 1024L * 1024L,
			totalBytes = 128L * 1024L * 1024L * 1024L,
			capturedAtMillis = 1_000L,
		)
		val end = StorageSnapshot(
			freeBytes = 40L * 1024L * 1024L * 1024L - 50L * 1024L * 1024L,
			totalBytes = 128L * 1024L * 1024L * 1024L,
			capturedAtMillis = 2_000L,
		)
		val summary = DeviceContextFormatter.formatStorageSummary(start, end)!!
		assertTrue(summary.contains("40.0 GB"))
		assertTrue(summary.contains("128.0 GB"))
		assertTrue(summary.contains("-50 MB"))
	}

	@Test
	fun `serialization roundtrip and TranscriptStore persistence work with defaults`() {
		val settings = DeviceContextSettings()
		assertTrue(settings.enabled)
		assertTrue(settings.includeLocation)
		assertFalse(settings.locationPromptedOnRecord)

		val original = RecordingDeviceContext(
			recordingId = 42L,
			capturedAtMillis = 1_700_000_000_000L,
			endedAtMillis = 1_700_000_060_000L,
			location = DeviceLocationSnapshot(
				latitude = 25.03396,
				longitude = 121.56447,
				accuracyMeters = 12f,
				address = "Taipei",
				capturedAtMillis = 1_700_000_000_100L,
				isPrecise = true,
			),
			batteryStart = BatterySnapshot(levelPercent = 80, capturedAtMillis = 1_700_000_000_000L),
			batteryEnd = BatterySnapshot(levelPercent = 78, capturedAtMillis = 1_700_000_060_000L),
			networkStart = NetworkSnapshot(
				transport = NetworkTransportType.WIFI,
				isConnected = true,
				wifiSsid = "Office",
				capturedAtMillis = 1_700_000_000_000L,
			),
			storageStart = StorageSnapshot(freeBytes = 20_000_000_000L, totalBytes = 64_000_000_000L, capturedAtMillis = 1_700_000_000_000L),
			storageEnd = StorageSnapshot(freeBytes = 19_990_000_000L, totalBytes = 64_000_000_000L, capturedAtMillis = 1_700_000_060_000L),
		)

		val encoded = json.encodeToString(RecordingDeviceContext.serializer(), original)
		val decoded = json.decodeFromString(RecordingDeviceContext.serializer(), encoded)
		assertEquals(original, decoded)

		val store = TranscriptStore(tempFolder.root)
		store.saveDeviceContext(42L, original)
		val loaded = store.deviceContext(42L)
		assertNotNull(loaded)
		assertEquals(original, loaded)

		val preview = DeviceContextFormatter.formatCompactPreview(loaded!!)!!
		assertTrue(preview.contains("Taipei"))
		assertTrue(preview.contains("80%→78%"))
		assertTrue(preview.contains("Wi-Fi"))

		store.deleteDeviceContext(42L)
		assertNull(store.deviceContext(42L))
	}
}
