package com.eva.drive.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.eva.drive.domain.provider.DriveNetworkChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal class AndroidDriveNetworkChecker @Inject constructor(
	@ApplicationContext private val context: Context,
) : DriveNetworkChecker {
	override fun isUnmeteredNetwork(network: Network?): Boolean {
		val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
			?: return false
		val targetNetwork = network ?: connectivityManager.activeNetwork ?: return false
		val capabilities = connectivityManager.getNetworkCapabilities(targetNetwork) ?: return false
		return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
			capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
	}
}
