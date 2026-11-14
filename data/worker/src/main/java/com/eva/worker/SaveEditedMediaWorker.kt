package com.eva.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eva.recordings.domain.tasks.SaveEditMediaItemTask
import com.eva.ui.R
import com.eva.utils.NotificationConstants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream


@HiltWorker
class SaveEditedMediaWorker @AssistedInject constructor(
	@Assisted private val context: Context,
	@Assisted private val params: WorkerParameters,
	private val task: SaveEditMediaItemTask,
) : CoroutineWorker(context, params) {

	override suspend fun doWork(): Result {
		val inputData = params.inputData

		val fileUri = inputData.getString(WorkerParams.WORK_DATA_FILE_URI)
			?: return Result.failure(
				workDataOf(
					WorkerParams.WORK_DATA_REQUIRED_ITEMS_NOT_FOUND to
							WorkerParams.WORK_DATA_SAVE_EDITED_ITEM_FILE_URI_NOT_PROVIDED
				)
			)

		val fileName = inputData.getString(WorkerParams.WORK_DATA_FILE_NAME)
			?: return Result.failure(
				workDataOf(
					WorkerParams.WORK_DATA_REQUIRED_ITEMS_NOT_FOUND to
							WorkerParams.WORK_DATA_SAVE_EDITED_ITEM_FILE_NAME_NOT_PROVIDED
				)
			)

		val mimeType = inputData.getString(WorkerParams.WORK_DATA_FILE_MIME_TYPE)
			?: return Result.failure(
				workDataOf(
					WorkerParams.WORK_DATA_REQUIRED_ITEMS_NOT_FOUND to
							WorkerParams.WORK_DATA_SAVE_EDITED_ITEM_FILE_NAME_NOT_PROVIDED
				)
			)

		return try {
			val file = fileUri.toUri().toFile()

			if (!file.exists()) return Result.failure(
				workDataOf(
					WorkerParams.WORK_DATA_REQUIRED_ITEMS_NOT_FOUND to
							WorkerParams.WORK_DATA_SAVE_EDITED_ITEM_FILE_INVALID
				)
			)

			val result = task.invoke(fileName, mimeType) { contentUri ->
				copyStreamData(
					inputFile = file,
					outputUri = contentUri.toUri(),
					onProgress = {
						val progress = (it * 100).toInt()
						setProgress(workDataOf(PROGRESS to progress))
						setForeground(createForegroundInfo(progress))
					},
				)
			}
			result.fold(
				onSuccess = {
					val isSuccess = removeTransformsFile(file)
					Log.d(TAG, "REMOVING FILE AFTER COPY :$isSuccess")
					Result.success(
						workDataOf(
							WorkerParams.WORK_SAVE_EDITED_ITEM_GOOD to true,
							WorkerParams.WORK_SAVE_EDITED_ITEM_REMOVE_EXTRA_FILE to isSuccess
						)
					)
				},
				onFailure = { exception ->
					val message = exception.message ?: "Unknown"
					Result.failure(
						workDataOf(WorkerParams.WORK_SAVE_EDITED_ITEM_FAILED to message)
					)
				},
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			val message = e.message ?: "Unknown"
			Result.failure(
				workDataOf(WorkerParams.WORK_SAVE_EDITED_ITEM_FAILED to message)
			)
		}
	}

	override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(0)

	private fun createForegroundInfo(progress: Int): ForegroundInfo {
		val title = applicationContext.getString(R.string.save_edit_item_work_title)
		val text = applicationContext.getString(R.string.save_edit_item_work_text)

		val notification =
			Notification.Builder(applicationContext, NotificationConstants.RECORDING_CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_edit)
				.setOngoing(true)
				.setContentTitle(title)
				.setProgress(100, progress, false)
				.setContentText(text)
				.build()

		return ForegroundInfo(
			NotificationConstants.SAVE_EDITED_MEDIA_FOREGROUND_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
		)
	}

	private suspend fun copyStreamData(
		inputFile: File,
		outputUri: Uri,
		bufferSize: Int = 2048,
		onProgress: suspend (Float) -> Unit = {}
	) = withContext(Dispatchers.IO) {
		val outputStream = context.contentResolver.openOutputStream(outputUri, "w")
			?: throw java.io.IOException("Cannot open output stream for $outputUri")
		outputStream.use { outStream ->
			inputFile.inputStream().use { inputStream ->
				copyStreamsWithProgress(
					inputStream = inputStream,
					outputStream = outStream,
					totalBytes = inputFile.length(),
					bufferSize = bufferSize,
					onProgress = onProgress,
				)
				Log.d(TAG, "CONTENT COPIED")
			}
		}
	}

	suspend fun removeTransformsFile(file: File): Boolean {
		return withContext(Dispatchers.IO) {
			try {
				// as this file will be mostly a temp file if not deleted properly
				// there is less chance of an issue
				if (!file.exists()) return@withContext false
				if (file.extension != "tmp") return@withContext false
				file.delete()
			} catch (e: Exception) {
				e.printStackTrace()
				false
			}
		}
	}

	companion object {

		private const val PROGRESS = "PROGRESS"
		private const val TAG = "SAVE_EDIT_ITEM_WORKER"

		internal suspend fun copyStreamsWithProgress(
			inputStream: InputStream,
			outputStream: OutputStream,
			totalBytes: Long,
			bufferSize: Int = 2048,
			onProgress: suspend (Float) -> Unit = {},
		) {
			var bytesCopied = 0L
			val buffer = ByteArray(bufferSize)
			while (true) {
				val bytesRead = inputStream.read(buffer)
				if (bytesRead < 0) break
				if (bytesRead == 0) continue
				outputStream.write(buffer, 0, bytesRead)
				bytesCopied += bytesRead
				if (totalBytes > 0L) {
					onProgress((bytesCopied.toFloat() / totalBytes).coerceIn(0f, 1f))
				}
			}
			outputStream.flush()
			if (totalBytes <= 0L) {
				onProgress(1f)
			}
		}

		fun startWorkerAndObserve(
			context: Context,
			fileUri: String,
			fileName: String,
			mimeType: String,
		) {
			val constraints = Constraints.Builder()
				.setRequiresStorageNotLow(true)
				.build()

			val data = Data.Builder()
				.putString(WorkerParams.WORK_DATA_FILE_URI, fileUri)
				.putString(WorkerParams.WORK_DATA_FILE_NAME, fileName)
				.putString(WorkerParams.WORK_DATA_FILE_MIME_TYPE, mimeType)
				.build()

			val workRequest = OneTimeWorkRequestBuilder<SaveEditedMediaWorker>()
				.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
				.setInputData(data)
				.addTag(TAG)
				.setConstraints(constraints)
				.build()

			val workManager = WorkManager.getInstance(context)
			workManager.enqueue(workRequest)
		}

		suspend fun observeWorkerState(
			context: Context,
			onProgress: (Int) -> Unit,
			onDone: () -> Unit,
			onFailed: (String?) -> Unit
		) {
			val workManager = WorkManager.getInstance(context)

			workManager.getWorkInfosByTagFlow(TAG)
				.collect { workInfo ->
					val info = workInfo.firstOrNull() ?: return@collect
					when (info.state) {
						WorkInfo.State.RUNNING -> {
							val progress = info.progress.getInt(PROGRESS, -1)
							if (progress != -1) onProgress(progress)
						}

						WorkInfo.State.SUCCEEDED -> onDone()
						WorkInfo.State.FAILED -> {
							val failedData =
								info.outputData.getString(WorkerParams.WORK_SAVE_EDITED_ITEM_FAILED)
							val invalidData =
								info.outputData.getString(WorkerParams.WORK_DATA_REQUIRED_ITEMS_NOT_FOUND)
							onFailed(failedData ?: invalidData)
						}

						else -> {}
					}
				}
		}
	}
}