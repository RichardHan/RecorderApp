package com.eva.drive.data.repository

import android.content.Context
import android.net.Network
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
import com.eva.drive.domain.provider.DriveNetworkChecker
import com.eva.drive.domain.provider.DriveUploadMeteredNetworkException
import com.eva.drive.domain.provider.DriveUploadOptedOutException
import com.eva.drive.domain.provider.DriveUploadPrecheck
import com.eva.drive.domain.provider.DriveUploadState
import com.eva.drive.domain.provider.DriveUploader
import com.eva.drive.domain.provider.RecordingUploadTask
import com.eva.drive.domain.repository.DriveSettingsRepository
import com.eva.drive.worker.DriveUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal class RecordingUploadTaskImpl internal constructor(
	private val context: Context,
	private val settingsRepository: DriveSettingsRepository,
	private val recordingsProvider: DriveRecordingsProvider,
	private val uploadDao: DriveUploadDao,
	private val uploader: DriveUploader,
	private val networkChecker: DriveNetworkChecker,
	private val workManagerProvider: () -> WorkManager,
) : RecordingUploadTask {

	@Inject
	constructor(
		@ApplicationContext context: Context,
		settingsRepository: DriveSettingsRepository,
		recordingsProvider: DriveRecordingsProvider,
		uploadDao: DriveUploadDao,
		uploader: DriveUploader,
		networkChecker: DriveNetworkChecker,
	) : this(
		context = context,
		settingsRepository = settingsRepository,
		recordingsProvider = recordingsProvider,
		uploadDao = uploadDao,
		uploader = uploader,
		networkChecker = networkChecker,
		workManagerProvider = { WorkManager.getInstance(context) },
	)
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
		workManagerProvider().enqueueUniqueWork(
			"${DriveUploadWorker.WORK_TAG}_$recordingId",
			ExistingWorkPolicy.KEEP,
			request,
		)
	}

	override suspend fun evaluatePreconditions(
		recordingId: Long,
		workId: UUID?,
		network: Network?,
	): DriveUploadPrecheck {
		val settings = settingsRepository.currentSettings()
		if (!settings.autoUploadEnabled || !settings.isSignedIn) {
			clearNonTerminalUploadState(recordingId)
			return DriveUploadPrecheck.OPTED_OUT
		}
		if (settings.wifiOnly && !networkChecker.isUnmeteredNetwork(network)) {
			updateWorkToUnmetered(recordingId, workId)
			return DriveUploadPrecheck.WAIT_FOR_UNMETERED
		}
		return DriveUploadPrecheck.ALLOWED
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

	override suspend fun upload(
		recordingId: Long,
		accessToken: String,
		workId: UUID?,
		network: Network?,
	): Result<String> {
		when (evaluatePreconditions(recordingId, workId, network)) {
			DriveUploadPrecheck.OPTED_OUT -> return Result.failure(DriveUploadOptedOutException())
			DriveUploadPrecheck.WAIT_FOR_UNMETERED ->
				return Result.failure(DriveUploadMeteredNetworkException())
			DriveUploadPrecheck.ALLOWED -> Unit
		}
		val recordingResult = recordingsProvider.recording(recordingId)
		return recordingResult.fold(
			onSuccess = { recording ->
				try {
					val settings = settingsRepository.currentSettings()
					if (!settings.autoUploadEnabled || !settings.isSignedIn) {
						closeQuietly(recording.media.inputStream)
						throw DriveUploadOptedOutException()
					}
					if (settings.wifiOnly && !networkChecker.isUnmeteredNetwork(network)) {
						closeQuietly(recording.media.inputStream)
						updateWorkToUnmetered(recordingId, workId)
						throw DriveUploadMeteredNetworkException()
					}
					uploadDao.upsert(
						DriveUploadEntity(
							recordingId = recordingId,
							status = DriveUploadStatus.RUNNING.name,
							updatedAt = System.currentTimeMillis(),
						),
					)
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
				} catch (e: CancellationException) {
					throw e
				} catch (e: DriveUploadOptedOutException) {
					clearNonTerminalUploadState(recordingId)
					Result.failure(e)
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

	private suspend fun clearNonTerminalUploadState(recordingId: Long) {
		val existing = uploadDao.getUploadFromId(recordingId)
		if (existing != null && existing.status != DriveUploadStatus.SUCCESS.name) {
			uploadDao.remove(recordingId)
		}
	}

	private fun updateWorkToUnmetered(recordingId: Long, workId: UUID?) {
		if (workId == null) return
		val constraints = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.UNMETERED)
			.build()
		val inputData = Data.Builder()
			.putLong(DriveUploadWorker.RECORDING_ID, recordingId)
			.build()
		val updatedRequest = OneTimeWorkRequestBuilder<DriveUploadWorker>()
			.setId(workId)
			.setInputData(inputData)
			.setConstraints(constraints)
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
			.addTag(DriveUploadWorker.WORK_TAG)
			.build()
		runCatching {
			workManagerProvider().updateWork(updatedRequest)
		}
	}

	private fun closeQuietly(stream: java.io.InputStream) {
		try {
			stream.close()
		} catch (_: IOException) {
		}
	}
}
