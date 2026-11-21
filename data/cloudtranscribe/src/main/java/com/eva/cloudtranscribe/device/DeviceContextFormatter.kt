package com.eva.cloudtranscribe.device

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure-Kotlin formatting and normalization helpers for [RecordingDeviceContext] (no Android runtime required). */
object DeviceContextFormatter {

	/**
	 * Returns battery percentage change during recording (`end - start`), e.g. `-3` for 3% drain or `+5` for 5% charged.
	 * Returns `null` when either endpoint is missing or outside `0..100`.
	 */
	fun batteryDeltaPercent(start: BatterySnapshot?, end: BatterySnapshot?): Int? {
		val s = start?.levelPercent?.takeIf { it in 0..100 } ?: return null
		val e = end?.levelPercent?.takeIf { it in 0..100 } ?: return null
		return e - s
	}

	/**
	 * Normalizes a raw Wi-Fi SSID from Android APIs: strips surrounding quotes and discards placeholders such as
	 * `<unknown ssid>` or empty strings.
	 */
	fun sanitizeWifiSsid(raw: String?): String? {
		val trimmed = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
		if (trimmed.isEmpty()) return null
		val lower = trimmed.lowercase(Locale.ROOT)
		if (lower == "<unknown ssid>" || lower == "0x" || lower == "unknown") return null
		return trimmed
	}

	/**
	 * Maps `TelephonyManager.NETWORK_TYPE_*` integer constants to a short generation label without importing Android SDK
	 * types so the mapping stays JVM-testable.
	 */
	fun cellularGenerationFromNetworkType(networkType: Int): String? = when (networkType) {
		// 5G NR (NETWORK_TYPE_NR = 20)
		20 -> "5G"
		// 4G LTE / LTE_CA (13 = LTE, 19 = LTE_CA)
		13, 19 -> "4G LTE"
		// 3G (3 = UMTS, 5 = EVDO_0, 6 = EVDO_A, 8 = HSDPA, 9 = HSUPA, 10 = HSPA, 12 = EVDO_B, 14 = EHRPD, 15 = HSPAP, 17 = TD_SCDMA)
		3, 5, 6, 8, 9, 10, 12, 14, 15, 17 -> "3G"
		// 2G (1 = GPRS, 2 = EDGE, 4 = CDMA, 7 = 1xRTT, 11 = IDEN, 16 = GSM)
		1, 2, 4, 7, 11, 16 -> "2G"
		else -> null
	}

	fun formatBytes(bytes: Long): String {
		if (bytes <= 0L) return "0 MB"
		val gb = 1024.0 * 1024.0 * 1024.0
		val mb = 1024.0 * 1024.0
		return if (bytes >= gb) {
			String.format(Locale.US, "%.1f GB", bytes / gb)
		} else {
			String.format(Locale.US, "%d MB", (bytes / mb).roundToInt().coerceAtLeast(1))
		}
	}

	fun formatCoordinates(latitude: Double, longitude: Double): String =
		String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

	fun formatLocationSummary(location: DeviceLocationSnapshot?): String? {
		location ?: return null
		val coords = formatCoordinates(location.latitude, location.longitude)
		val accText = location.accuracyMeters?.takeIf { it > 0f }?.let { "±${it.roundToInt()} m" }
		val coordWithAcc = if (accText != null) "$coords（$accText）" else coords
		val addr = location.address?.trim()?.takeIf { it.isNotEmpty() }
		return if (addr != null) "$addr\n$coordWithAcc" else coordWithAcc
	}

	fun formatBatterySummary(start: BatterySnapshot?, end: BatterySnapshot?): String? {
		start ?: return null
		val startPct = start.levelPercent.takeIf { it in 0..100 } ?: return null
		val delta = batteryDeltaPercent(start, end)
		val endPct = end?.levelPercent?.takeIf { it in 0..100 }
		val levelPart = when {
			endPct != null && delta != null -> {
				val sign = if (delta > 0) "+" else ""
				"$startPct% → $endPct% ($sign$delta%)"
			}
			else -> "$startPct%"
		}
		val extras = buildList {
			if (start.isCharging) {
				val plugged = when (start.pluggedType) {
					BatteryPluggedType.AC -> UiStrings.get(StringsRes.string.data_cloudtranscribe_device_plugged_ac, "AC")
					BatteryPluggedType.USB -> "USB"
					BatteryPluggedType.WIRELESS -> UiStrings.get(StringsRes.string.data_cloudtranscribe_device_plugged_wireless, "無線充電")
					BatteryPluggedType.DOCK -> UiStrings.get(StringsRes.string.data_cloudtranscribe_device_plugged_dock, "底座")
					else -> null
				}
				val chargingText = UiStrings.get(StringsRes.string.data_cloudtranscribe_device_charging, "充電中")
				add(if (plugged != null) "$chargingText・$plugged" else chargingText)
			}
			start.temperatureCelsius?.let { add(String.format(Locale.US, "%.1f°C", it)) }
			if (start.isPowerSaveMode == true) {
				add(UiStrings.get(StringsRes.string.data_cloudtranscribe_device_power_save, "省電模式"))
			}
		}
		return if (extras.isEmpty()) levelPart else "$levelPart（${extras.joinToString("・")}）"
	}

	fun formatNetworkSummary(network: NetworkSnapshot?): String? {
		network ?: return null
		val base = when (network.transport) {
			NetworkTransportType.WIFI -> {
				val ssid = sanitizeWifiSsid(network.wifiSsid)
				if (ssid != null) "Wi-Fi ($ssid)" else "Wi-Fi"
			}
			NetworkTransportType.CELLULAR -> {
				val label = UiStrings.get(StringsRes.string.data_cloudtranscribe_device_cellular, "行動網路")
				val gen = network.cellularGeneration?.takeIf { it.isNotBlank() }
				if (gen != null) "$label ($gen)" else label
			}
			NetworkTransportType.ETHERNET -> UiStrings.get(StringsRes.string.data_cloudtranscribe_device_ethernet, "乙太網路")
			NetworkTransportType.OTHER -> UiStrings.get(StringsRes.string.data_cloudtranscribe_device_network_other, "其他網路")
			NetworkTransportType.NONE -> UiStrings.get(StringsRes.string.data_cloudtranscribe_device_network_none, "無網路連線")
		}
		val tags = buildList {
			if (network.isAirplaneMode == true) add(UiStrings.get(StringsRes.string.data_cloudtranscribe_device_airplane_mode, "飛航模式"))
			if (network.isVpnActive) add("VPN")
			if (network.isMetered == true && network.transport != NetworkTransportType.NONE) {
				add(UiStrings.get(StringsRes.string.data_cloudtranscribe_device_metered, "計量網路"))
			}
		}
		return if (tags.isEmpty()) base else "$base（${tags.joinToString("・")}）"
	}

	fun formatStorageSummary(start: StorageSnapshot?, end: StorageSnapshot?): String? {
		start ?: return null
		if (start.totalBytes <= 0L) return null
		val freeText = formatBytes(start.freeBytes)
		val totalText = formatBytes(start.totalBytes)
		val base = UiStrings.get(
			StringsRes.string.data_cloudtranscribe_device_storage_free_total,
			"可用 %1\$s / 共 %2\$s",
			freeText,
			totalText,
		)
		val endFree = end?.freeBytes
		if (endFree != null && start.freeBytes > 0L) {
			val diff = endFree - start.freeBytes
			if (abs(diff) >= 1024L * 1024L) {
				val sign = if (diff > 0L) "+" else "-"
				val endLabel = UiStrings.get(
					StringsRes.string.data_cloudtranscribe_device_storage_end_delta,
					"結束 %1\$s（%2\$s%3\$s）",
					formatBytes(endFree),
					sign,
					formatBytes(abs(diff)),
				)
				return "$base・$endLabel"
			}
		}
		return base
	}

	fun formatCompactPreview(context: RecordingDeviceContext): String? {
		val parts = buildList {
			context.location?.let { loc ->
				val addr = loc.address?.trim()?.takeIf { it.isNotEmpty() }
				add(addr ?: formatCoordinates(loc.latitude, loc.longitude))
			}
			context.batteryStart?.levelPercent?.takeIf { it in 0..100 }?.let { startPct ->
				val endPct = context.batteryEnd?.levelPercent?.takeIf { it in 0..100 }
				add(if (endPct != null) "$startPct%→$endPct%" else "$startPct%")
			}
			context.networkStart?.let { net ->
				val netLabel = when (net.transport) {
					NetworkTransportType.WIFI -> "Wi-Fi"
					NetworkTransportType.CELLULAR -> net.cellularGeneration ?: UiStrings.get(StringsRes.string.data_cloudtranscribe_device_cellular, "行動網路")
					NetworkTransportType.ETHERNET -> UiStrings.get(StringsRes.string.data_cloudtranscribe_device_ethernet, "乙太網路")
					NetworkTransportType.OTHER -> UiStrings.get(StringsRes.string.data_cloudtranscribe_device_network_other, "其他網路")
					NetworkTransportType.NONE -> if (net.isAirplaneMode == true) UiStrings.get(StringsRes.string.data_cloudtranscribe_device_airplane_mode, "飛航模式") else UiStrings.get(StringsRes.string.data_cloudtranscribe_device_network_none, "無網路")
				}
				add(netLabel)
			}
			context.storageStart?.takeIf { it.totalBytes > 0L }?.let { st ->
				add(UiStrings.get(StringsRes.string.data_cloudtranscribe_device_storage_short_free, "餘 %1\$s", formatBytes(st.freeBytes)))
			}
		}
		return parts.takeIf { it.isNotEmpty() }?.joinToString("・")
	}
}
