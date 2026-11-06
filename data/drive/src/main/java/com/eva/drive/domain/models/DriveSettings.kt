package com.eva.drive.domain.models

data class DriveSettings(
	val accountEmail: String? = null,
	val autoUploadEnabled: Boolean = false,
	val wifiOnly: Boolean = false,
	val targetFolderId: String? = null,
) {
	val isSignedIn: Boolean get() = !accountEmail.isNullOrBlank()
}

enum class DriveUploadStatus {
	PENDING,
	RUNNING,
	SUCCESS,
	FAILED,
}
