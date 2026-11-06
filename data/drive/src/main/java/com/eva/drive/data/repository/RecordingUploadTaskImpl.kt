package com.eva.drive.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.eva.database.dao.DriveUploadDao
import com.eva.database.entity.DriveUploadEntity
import com.eva.drive.R
import com.eva.drive.domain.models.DriveUploadStatus
import com.eva.drive.domain.provider.DriveUploadState
import com.eva.drive.domain.provider.DriveUploader
import com.eva.drive.domain.provider.RecordingUploadTask
import com.eva.drive.domain.repository.DriveSettingsRepository
import com.eva.drive.worker.DriveUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

internal class RecordingUploadTaskImpl @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settingsRepository: DriveSettingsRepository,
	private val recordingsProvider: DriveRecordingsProvider,
	private val uploadDao: DriveUploadDao,
	private val uploader: DriveUploader,
) : RecordingUploadTask {
	override fun observe(recordingId: Long): Flow<DriveUploadState?> =
		uploadDao.getUploadFromIdAsFlow(recordingId).map { entity ->
			entity?.run {
				DriveUploadState(
					recordingId = recordingId,
					status = DriveUploadStatus.valueOf(status),
					error = errorMessage,
					fileId = fileId,
				)
			}
		}

	override suspend fun enqueue(recordingId: Long) {
		val settings = settingsRepository.currentSettings()
		if (!settings.autoUploadEnabled || !settings.isSignedIn) return
		val existing = uploadDao.getUploadFromId(recordingId)
		if (existing?.status == DriveUploadStatus.SUCCESS.name) return
		uploadDao.upsert(
			DriveUploadEntity(
				recordingId = recordingId,
				status = DriveUploadStatus.PENDING.name,
				updatedAt = System.currentTimeMillis(),
			),
		)
		val network = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
		val constraints = Constraints.Builder().setRequiredNetworkType(network).build()
		val inputData = Data.Builder().putLong(DriveUploadWorker.RECORDING_ID, recordingId).build()
		val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
			.setInputData(inputData)
			.setConstraints(constraints)
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
			.addTag(DriveUploadWorker.WORK_TAG)
			.build()
		WorkManager.getInstance(context).enqueueUniqueWork(
			"${DriveUploadWorker.WORK_TAG}_$recordingId",
			ExistingWorkPolicy.KEEP,
			request,
		)
	}

	override suspend fun markFailed(recordingId: Long, message: String?) {
		uploadDao.upsert(
			DriveUploadEntity(
				recordingId = recordingId,
				status = DriveUploadStatus.FAILED.name,
				errorMessage = message,
				updatedAt = System.currentTimeMillis(),
			),
		)
	}

	override suspend fun upload(recordingId: Long, accessToken: String): Result<String> {
		val recordingResult = recordingsProvider.recording(recordingId)
		return recordingResult.fold(
			onSuccess = { recording ->
				try {
					uploadDao.upsert(
						DriveUploadEntity(
							recordingId = recordingId,
							status = DriveUploadStatus.RUNNING.name,
							updatedAt = System.currentTimeMillis(),
						),
					)
					val settings = settingsRepository.currentSettings()
					val uploaded = withContext(Dispatchers.IO) {
						uploader.upload(
							accessToken = accessToken,
							targetFolderId = settings.targetFolderId,
							preferredFolderName = context.getString(R.string.default_drive_upload_folder),
							recording = recording,
						)
					}
					if (settings.targetFolderId == null) {
						settingsRepository.setTargetFolderId(uploaded.folderId)
					}
					uploadDao.upsert(
						DriveUploadEntity(
							recordingId = recordingId,
							status = DriveUploadStatus.SUCCESS.name,
							fileId = uploaded.id,
							updatedAt = System.currentTimeMillis(),
						),
					)
					Result.success(uploaded.id)
				} catch (e: kotlinx.coroutines.CancellationException) {
					throw e
				} catch (e: Exception) {
					uploadDao.upsert(
						DriveUploadEntity(
							recordingId = recordingId,
							status = DriveUploadStatus.PENDING.name,
							errorMessage = e.message,
							updatedAt = System.currentTimeMillis(),
						),
					)
					Result.failure(e)
				}
			},
			onFailure = { error ->
				uploadDao.upsert(
					DriveUploadEntity(
						recordingId = recordingId,
						status = DriveUploadStatus.FAILED.name,
						errorMessage = error.message,
						updatedAt = System.currentTimeMillis(),
					),
				)
				Result.failure(error)
			},
		)
	}
}
