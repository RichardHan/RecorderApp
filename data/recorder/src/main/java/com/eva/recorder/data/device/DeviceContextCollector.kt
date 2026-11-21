package com.eva.recorder.data.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.eva.cloudtranscribe.device.BatteryPluggedType
import com.eva.cloudtranscribe.device.BatterySnapshot
import com.eva.cloudtranscribe.device.DeviceContextFormatter
import com.eva.cloudtranscribe.device.DeviceLocationSnapshot
import com.eva.cloudtranscribe.device.NetworkSnapshot
import com.eva.cloudtranscribe.device.NetworkTransportType
import com.eva.cloudtranscribe.device.StorageSnapshot
import com.eva.location.domain.repository.LocationAddressProvider
import com.eva.location.domain.repository.LocationProvider
import com.eva.recordings.domain.provider.StorageInfoProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DEVICE_CTX_COLLECTOR"
private const val LOCATION_TIMEOUT_MS = 4_500L
private const val GEOCODER_TIMEOUT_MS = 3_500L

interface DeviceContextCollector {
	fun captureBattery(): BatterySnapshot?
	fun captureNetwork(): NetworkSnapshot?
	suspend fun captureStorage(): StorageSnapshot?
	suspend fun captureLocation(): DeviceLocationSnapshot?
}

internal class AndroidDeviceContextCollector(
	private val context: Context,
	private val locationProvider: LocationProvider,
	private val addressProvider: LocationAddressProvider,
	private val storageInfoProvider: StorageInfoProvider,
	private val clock: () -> Long = { System.currentTimeMillis() },
) : DeviceContextCollector {

	private fun hasPermission(permission: String): Boolean =
		ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

	override fun captureBattery(): BatterySnapshot? {
		return try {
			val now = clock()
			val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
			val batteryManager = context.getSystemService<BatteryManager>()
			val powerManager = context.getSystemService<PowerManager>()

			val levelFromIntent = intent?.let {
				val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
				val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
				if (level >= 0 && scale > 0) (level * 100) / scale else -1
			} ?: -1
			val levelPercent = if (levelFromIntent in 0..100) {
				levelFromIntent
			} else {
				batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
					?.takeIf { it in 0..100 } ?: -1
			}

			val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
			val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
				status == BatteryManager.BATTERY_STATUS_FULL ||
				(batteryManager?.isCharging == true)

			val pluggedRaw = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
			val pluggedType = when (pluggedRaw) {
				BatteryManager.BATTERY_PLUGGED_AC -> BatteryPluggedType.AC
				BatteryManager.BATTERY_PLUGGED_USB -> BatteryPluggedType.USB
				BatteryManager.BATTERY_PLUGGED_WIRELESS -> BatteryPluggedType.WIRELESS
				BatteryManager.BATTERY_PLUGGED_DOCK -> BatteryPluggedType.DOCK
				0 -> BatteryPluggedType.NONE
				else -> BatteryPluggedType.OTHER
			}

			val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
			val temperatureCelsius = if (tempTenths != Int.MIN_VALUE && tempTenths > -1000 && tempTenths < 1500) {
				tempTenths / 10f
			} else null

			val isPowerSaveMode = powerManager?.isPowerSaveMode

			BatterySnapshot(
				levelPercent = levelPercent,
				isCharging = isCharging,
				pluggedType = pluggedType,
				temperatureCelsius = temperatureCelsius,
				isPowerSaveMode = isPowerSaveMode,
				capturedAtMillis = now,
			)
		} catch (e: Exception) {
			Log.w(TAG, "Failed to capture battery snapshot", e)
			null
		}
	}

	override fun captureNetwork(): NetworkSnapshot? {
		return try {
			val now = clock()
			val cm = context.getSystemService<ConnectivityManager>()
			val activeNetwork = cm?.activeNetwork
			val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }

			val isAirplaneMode = runCatching {
				Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
			}.getOrNull()

			val transport = when {
				caps == null -> NetworkTransportType.NONE
				caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransportType.WIFI
				caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransportType.CELLULAR
				caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransportType.ETHERNET
				else -> NetworkTransportType.OTHER
			}

			val isConnected = caps != null && (
				caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
					transport != NetworkTransportType.NONE
				)
			val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
			val isMetered = if (caps != null) cm.isActiveNetworkMetered else null

			val wifiSsid = if (transport == NetworkTransportType.WIFI) {
				readWifiSsidIfPermitted(caps)
			} else null

			val cellularGen = if (transport == NetworkTransportType.CELLULAR) {
				readCellularGenerationIfPermitted()
			} else null

			NetworkSnapshot(
				transport = transport,
				isConnected = isConnected,
				wifiSsid = wifiSsid,
				cellularGeneration = cellularGen,
				isMetered = isMetered,
				isVpnActive = isVpn,
				isAirplaneMode = isAirplaneMode,
				capturedAtMillis = now,
			)
		} catch (e: Exception) {
			Log.w(TAG, "Failed to capture network snapshot", e)
			null
		}
	}

	private fun readWifiSsidIfPermitted(caps: NetworkCapabilities?): String? {
		val hasLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
			hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
		if (!hasLocation) return null

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			val fromCaps = (caps?.transportInfo as? WifiInfo)?.ssid
			DeviceContextFormatter.sanitizeWifiSsid(fromCaps)?.let { return it }
		}
		@Suppress("DEPRECATION")
		val wifiManager = context.applicationContext.getSystemService<WifiManager>()
		@Suppress("DEPRECATION")
		val raw = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
		return DeviceContextFormatter.sanitizeWifiSsid(raw)
	}

	@SuppressLint("MissingPermission")
	private fun readCellularGenerationIfPermitted(): String? {
		if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return null
		val tm = context.getSystemService<TelephonyManager>() ?: return null
		val type = try {
			tm.dataNetworkType
		} catch (_: SecurityException) {
			return null
		} catch (_: Exception) {
			return null
		}
		return DeviceContextFormatter.cellularGenerationFromNetworkType(type)
	}

	override suspend fun captureStorage(): StorageSnapshot? {
		return withContext(Dispatchers.IO) {
			try {
				val now = clock()
				val fromProvider = storageInfoProvider.invoke().getOrNull()
				if (fromProvider != null && fromProvider.totalAmountInBytes > 0L) {
					return@withContext StorageSnapshot(
						freeBytes = fromProvider.freeAmountInBytes,
						totalBytes = fromProvider.totalAmountInBytes,
						capturedAtMillis = now,
					)
				}
				val stat = StatFs(Environment.getDataDirectory().path)
				StorageSnapshot(
					freeBytes = stat.availableBytes,
					totalBytes = stat.totalBytes,
					capturedAtMillis = now,
				)
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				Log.w(TAG, "Failed to capture storage snapshot", e)
				null
			}
		}
	}

	override suspend fun captureLocation(): DeviceLocationSnapshot? {
		val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
		val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
		if (!hasFine && !hasCoarse) return null

		return try {
			val locationResult = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
				locationProvider(fetchCurrentIfNotFound = true)
			}
			val loc = locationResult?.getOrNull() ?: return null
			val address = withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
				addressProvider(loc).getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
			}
			DeviceLocationSnapshot(
				latitude = loc.latitude,
				longitude = loc.longitude,
				accuracyMeters = loc.accuracy?.takeIf { it > 0f },
				address = address,
				capturedAtMillis = clock(),
				isPrecise = hasFine,
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.w(TAG, "Failed to capture location snapshot", e)
			null
		}
	}
}
