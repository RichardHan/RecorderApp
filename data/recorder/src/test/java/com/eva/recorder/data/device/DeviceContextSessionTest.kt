package com.eva.recorder.data.device

import com.eva.cloudtranscribe.device.BatteryPluggedType
import com.eva.cloudtranscribe.device.BatterySnapshot
import com.eva.cloudtranscribe.device.DeviceContextFormatter
import com.eva.cloudtranscribe.device.DeviceContextSettings
import com.eva.cloudtranscribe.device.DeviceContextSettingsRepository
import com.eva.cloudtranscribe.device.DeviceLocationSnapshot
import com.eva.cloudtranscribe.device.NetworkSnapshot
import com.eva.cloudtranscribe.device.NetworkTransportType
import com.eva.cloudtranscribe.device.StorageSnapshot
import com.eva.cloudtranscribe.store.TranscriptStore
import io.mockk.coEvery
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceContextSessionTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	@After
	fun tearDown() {
		scope.cancel()
	}

	@Test
	fun `beginSession and finishSession persist start and end snapshots with battery delta`() = runBlocking {
		val store = TranscriptStore(tempFolder.root)
		val settingsRepo = mockk<DeviceContextSettingsRepository>()
		coEvery { settingsRepo.current() } returns DeviceContextSettings(enabled = true, includeLocation = true)

		val batteryCalls = AtomicInteger(0)
		val storageCalls = AtomicInteger(0)
		val collector = object : DeviceContextCollector {
			override fun captureBattery(): BatterySnapshot {
				val call = batteryCalls.incrementAndGet()
				return if (call == 1) {
					BatterySnapshot(levelPercent = 90, isCharging = false, pluggedType = BatteryPluggedType.NONE, capturedAtMillis = 1_000L)
				} else {
					BatterySnapshot(levelPercent = 87, isCharging = false, pluggedType = BatteryPluggedType.NONE, capturedAtMillis = 2_000L)
				}
			}

			override fun captureNetwork(): NetworkSnapshot = NetworkSnapshot(
				transport = NetworkTransportType.WIFI,
				isConnected = true,
				wifiSsid = "Studio",
				capturedAtMillis = 1_000L,
			)

			override suspend fun captureStorage(): StorageSnapshot {
				val call = storageCalls.incrementAndGet()
				return StorageSnapshot(
					freeBytes = if (call == 1) 50_000_000_000L else 49_990_000_000L,
					totalBytes = 128_000_000_000L,
					capturedAtMillis = if (call == 1) 1_000L else 2_000L,
				)
			}

			override suspend fun captureLocation(): DeviceLocationSnapshot = DeviceLocationSnapshot(
				latitude = 25.03396,
				longitude = 121.56447,
				accuracyMeters = 10f,
				address = "Taipei City",
				capturedAtMillis = 1_100L,
				isPrecise = true,
			)
		}

		var now = 1_000L
		val session = DeviceContextSession(collector, settingsRepo, store, scope) { now }
		session.beginSession()
		now = 61_000L
		session.finishSession(77L)

		val saved = store.deviceContext(77L)
		assertNotNull(saved)
		assertEquals(77L, saved!!.recordingId)
		assertEquals("Taipei City", saved.location?.address)
		assertEquals(90, saved.batteryStart?.levelPercent)
		assertEquals(87, saved.batteryEnd?.levelPercent)
		assertEquals(-3, DeviceContextFormatter.batteryDeltaPercent(saved.batteryStart, saved.batteryEnd))
		assertEquals("Studio", saved.networkStart?.wifiSsid)
		assertEquals(50_000_000_000L, saved.storageStart?.freeBytes)
		assertEquals(49_990_000_000L, saved.storageEnd?.freeBytes)
	}

	@Test
	fun `disabled setting skips capture and saves nothing`() = runBlocking {
		val store = TranscriptStore(tempFolder.root)
		val settingsRepo = mockk<DeviceContextSettingsRepository>()
		coEvery { settingsRepo.current() } returns DeviceContextSettings(enabled = false, includeLocation = true)

		val collector = mockk<DeviceContextCollector>(relaxed = true)
		val session = DeviceContextSession(collector, settingsRepo, store, scope)
		session.beginSession()
		session.finishSession(88L)

		assertNull(store.deviceContext(88L))
	}

	@Test
	fun `includeLocation false skips location while keeping battery, network, and storage`() = runBlocking {
		val store = TranscriptStore(tempFolder.root)
		val settingsRepo = mockk<DeviceContextSettingsRepository>()
		coEvery { settingsRepo.current() } returns DeviceContextSettings(enabled = true, includeLocation = false)

		val locationCalled = AtomicInteger(0)
		val collector = object : DeviceContextCollector {
			override fun captureBattery() = BatterySnapshot(levelPercent = 50, capturedAtMillis = 100L)
			override fun captureNetwork() = NetworkSnapshot(transport = NetworkTransportType.NONE, isAirplaneMode = true, capturedAtMillis = 100L)
			override suspend fun captureStorage() = StorageSnapshot(freeBytes = 10_000_000_000L, totalBytes = 64_000_000_000L, capturedAtMillis = 100L)
			override suspend fun captureLocation(): DeviceLocationSnapshot? {
				locationCalled.incrementAndGet()
				return null
			}
		}

		val session = DeviceContextSession(collector, settingsRepo, store, scope)
		session.beginSession()
		session.finishSession(99L)

		assertEquals(0, locationCalled.get())
		val saved = store.deviceContext(99L)
		assertNotNull(saved)
		assertNull(saved!!.location)
		assertEquals(50, saved.batteryStart?.levelPercent)
		assertEquals(true, saved.networkStart?.isAirplaneMode)
	}

	@Test
	fun `cancelSession discards active capture and cancels slow location`() = runBlocking {
		val store = TranscriptStore(tempFolder.root)
		val settingsRepo = mockk<DeviceContextSettingsRepository>()
		coEvery { settingsRepo.current() } returns DeviceContextSettings(enabled = true, includeLocation = true)

		val locationStarted = CompletableDeferred<Unit>()
		val locationCancelled = CompletableDeferred<Boolean>()
		val collector = object : DeviceContextCollector {
			override fun captureBattery() = BatterySnapshot(levelPercent = 70, capturedAtMillis = 100L)
			override fun captureNetwork() = NetworkSnapshot(transport = NetworkTransportType.WIFI, capturedAtMillis = 100L)
			override suspend fun captureStorage() = StorageSnapshot(freeBytes = 10_000_000_000L, totalBytes = 64_000_000_000L, capturedAtMillis = 100L)
			override suspend fun captureLocation(): DeviceLocationSnapshot? {
				try {
					locationStarted.complete(Unit)
					delay(10_000L)
					return null
				} finally {
					locationCancelled.complete(true)
				}
			}
		}

		val session = DeviceContextSession(collector, settingsRepo, store, scope)
		session.beginSession()
		locationStarted.await()
		session.cancelSession()
		session.finishSession(101L)

		assertEquals(true, locationCancelled.await())
		assertNull(store.deviceContext(101L))
	}
}
