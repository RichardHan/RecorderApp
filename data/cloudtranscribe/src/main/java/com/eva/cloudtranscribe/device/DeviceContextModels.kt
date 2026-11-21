package com.eva.cloudtranscribe.device

import kotlinx.serialization.Serializable

@Serializable
data class DeviceLocationSnapshot(
	val latitude: Double,
	val longitude: Double,
	val accuracyMeters: Float? = null,
	val address: String? = null,
	val capturedAtMillis: Long,
	val isPrecise: Boolean = false,
)

@Serializable
enum class BatteryPluggedType { NONE, AC, USB, WIRELESS, DOCK, OTHER }

@Serializable
data class BatterySnapshot(
	val levelPercent: Int,
	val isCharging: Boolean = false,
	val pluggedType: BatteryPluggedType = BatteryPluggedType.NONE,
	val temperatureCelsius: Float? = null,
	val isPowerSaveMode: Boolean? = null,
	val capturedAtMillis: Long,
)

@Serializable
enum class NetworkTransportType { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

@Serializable
data class NetworkSnapshot(
	val transport: NetworkTransportType = NetworkTransportType.NONE,
	val isConnected: Boolean = false,
	val wifiSsid: String? = null,
	val cellularGeneration: String? = null,
	val isMetered: Boolean? = null,
	val isVpnActive: Boolean = false,
	val isAirplaneMode: Boolean? = null,
	val capturedAtMillis: Long,
)

@Serializable
data class StorageSnapshot(
	val freeBytes: Long,
	val totalBytes: Long,
	val capturedAtMillis: Long,
)

/**
 * One-shot device context captured on-device when recording starts (and battery/storage updated when saved).
 * Saved locally in [com.eva.cloudtranscribe.store.TranscriptStore]; never sent to Slack or cloud by default.
 */
@Serializable
data class RecordingDeviceContext(
	val recordingId: Long = 0L,
	val capturedAtMillis: Long = 0L,
	val endedAtMillis: Long? = null,
	val location: DeviceLocationSnapshot? = null,
	val batteryStart: BatterySnapshot? = null,
	val batteryEnd: BatterySnapshot? = null,
	val networkStart: NetworkSnapshot? = null,
	val storageStart: StorageSnapshot? = null,
	val storageEnd: StorageSnapshot? = null,
)

@Serializable
data class DeviceContextSettings(
	val enabled: Boolean = true,
	val includeLocation: Boolean = true,
	val locationPromptedOnRecord: Boolean = false,
)
