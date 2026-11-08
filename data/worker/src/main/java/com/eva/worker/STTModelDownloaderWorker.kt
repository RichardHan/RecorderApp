package com.eva.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eva.transcribe.domain.ModelDownloadManager
import com.eva.transcribe.domain.models.ModelDownloadState
import com.eva.utils.NotificationConstants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.random.Random

@HiltWorker
internal class STTModelDownloaderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val params: WorkerParameters,
    private val task: ModelDownloadManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val modelId = params.inputData.getString(WorkerParams.WORK_DATA_STT_MODEL_ID)
            ?: return Result.failure(
                workDataOf(
                    WorkerParams.WORK_DATA_SST_MODEL_WORK_FAILED to WorkerParams.WORK_DATA_SST_MODEL_ID_MISSING,
                ),
            )

        val notificationMarker = Random.nextInt()

        val taskResult = task.downloadAndSaveModel(
            modelId = modelId,
            onDownloadState = { state ->
                val message = state.toProgressMessage()
                val percent = when (state) {
                    is ModelDownloadState.DownloadProgress -> state.progress
                    is ModelDownloadState.Processing -> state.progress
                    else -> null
                }
                if (percent != null) {
                    setProgress(
                        workDataOf(
                            WorkerParams.WORK_DATA_STT_MODEL_ID to modelId,
                            WorkerParams.WORK_DATA_WORK_PROGRESS_MESSAGE to message,
                            WorkerParams.WORK_DATA_WORK_PROGRESS_PERCENTAGE to percent,
                        ),
                    )
                } else {
                    setProgress(
                        workDataOf(
                            WorkerParams.WORK_DATA_STT_MODEL_ID to modelId,
                            WorkerParams.WORK_DATA_WORK_PROGRESS_MESSAGE to message,
                        ),
                    )
                }
                setForeground(createNotification(state, notificationMarker))
            },
        )
        if (taskResult.isFailure) {
            val exception = taskResult.exceptionOrNull()
            val reason = exception?.message ?: "Some unwanted issue happened"
            return Result.failure(
                workDataOf(
                    WorkerParams.WORK_DATA_STT_MODEL_ID to modelId,
                    WorkerParams.WORK_DATA_SST_MODEL_WORK_FAILED to reason,
                ),
            )
        }

        val isSuccess = taskResult.getOrNull() ?: false
        return Result.success(
            workDataOf(
                WorkerParams.WORK_DATA_STT_MODEL_ID to modelId,
                WorkerParams.WORK_DATA_SST_MODEL_WORK_SUCCESS to isSuccess,
            ),
        )
    }


    private fun createNotification(state: ModelDownloadState, notifyId: Int): ForegroundInfo {

        val title = applicationContext.getString(R.string.stt_model_download_notification_text)
        val intent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

        val text = state.toProgressMessage()

        val cancelAction = NotificationCompat.Action.Builder(
            IconCompat.createWithResource(applicationContext, R.drawable.ic_cancel),
            "Cancel",
            intent,
        ).build()

        val notification = NotificationCompat
            .Builder(applicationContext, NotificationConstants.WORKER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_model_download)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(cancelAction)

        when (state) {
            is ModelDownloadState.DownloadProgress -> {
                val progress = state.progress.coerceAtMost(100)
                notification.setProgress(100, progress, false)
            }

            is ModelDownloadState.Processing -> notification.setProgress(100, state.progress.coerceIn(0, 100), false)
            ModelDownloadState.ModelUnzipping -> notification.setProgress(100, 0, true)
            else -> notification.setProgress(0, 0, false)
        }

        return ForegroundInfo(
            NotificationConstants.DOWNLOAD_STT_MODEL_WORKER_NOTIFICATION_ID + notifyId,
            notification.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun ModelDownloadState.toProgressMessage(): String = when (this) {
        is ModelDownloadState.DownloadProgress -> "下載中"
        ModelDownloadState.DownloadInitiated -> "準備下載"
        ModelDownloadState.ModelUnzipping -> applicationContext.getString(R.string.stt_model_unzip_text)
        is ModelDownloadState.Processing -> when (stage) {
            ModelDownloadState.Stage.VERIFYING -> "驗證檔案"
            ModelDownloadState.Stage.EXTRACTING -> "解壓安裝（大模型需數分鐘）"
        }
        ModelDownloadState.ModelReadyToSave -> applicationContext.getString(R.string.stt_model_ready_text)
        ModelDownloadState.ModelSaved -> applicationContext.getString(R.string.stt_model_saved_text)
        ModelDownloadState.DownloadCleanUpDone -> applicationContext.getString(R.string.stt_model_cleanup_text)
    }
}
