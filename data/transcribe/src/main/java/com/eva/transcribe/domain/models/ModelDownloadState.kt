package com.eva.transcribe.domain.models

sealed class ModelDownloadState {
	data object DownloadInitiated : ModelDownloadState()
	data class DownloadProgress(val progress: Int) : ModelDownloadState()
	data object ModelUnzipping : ModelDownloadState()
	/** Post-download work on large archives (minutes on a phone): reported with its own percent so the UI never sits at 0 %. */
	data class Processing(val stage: Stage, val progress: Int) : ModelDownloadState()
	enum class Stage { VERIFYING, EXTRACTING }
	data object ModelReadyToSave : ModelDownloadState()
	data object ModelSaved : ModelDownloadState()
	data object DownloadCleanUpDone : ModelDownloadState()
}