package com.eva.drive.domain.provider

import android.net.Network

fun interface DriveNetworkChecker {
	fun isUnmeteredNetwork(network: Network?): Boolean
}
