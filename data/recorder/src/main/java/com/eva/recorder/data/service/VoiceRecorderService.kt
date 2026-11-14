@file:OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)

package com.eva.recorder.data.service

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.eva.bookmarks.domain.provider.RecordingBookmarksProvider
import com.eva.drive.data.preferences.RecordingAutoUploader
import com.eva.recorder.R
import com.eva.recorder.data.VoiceRecorderManager
import com.eva.recorder.domain.RecorderWidgetInteractor
import com.eva.recorder.domain.models.RecordedPoint
import com.eva.recorder.domain.models.RecorderAction
import com.eva.recorder.domain.models.RecorderState
import com.eva.use_case.usecases.BluetoothScoUseCase
import com.eva.use_case.usecases.PhoneStateObserverUseCase
import com.eva.utils.NotificationConstants
import com.eva.utils.Resource
import com.eva.utils.roundToClosestSeconds
import com.eva.utils.toDuration
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime


@AndroidEntryPoint
internal class VoiceRecorderService : LifecycleService() {

    @Inject
    lateinit var voiceRecorder: VoiceRecorderManager

    @Inject
    lateinit var bluetoothScoUseCase: BluetoothScoUseCase

    @Inject
    lateinit var phoneStateObserverUseCase: PhoneStateObserverUseCase

    @Inject
    lateinit var bookmarksProvider: RecordingBookmarksProvider

    @Inject
    lateinit var widgetFacade: RecorderWidgetInteractor

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var recordingAutoUploader: RecordingAutoUploader

    @Inject
    lateinit var transcriptStore: com.eva.cloudtranscribe.store.TranscriptStore

    @Inject
    lateinit var liveCloud: com.eva.recorder.data.recorder.LiveCloudCoordinator

    @Inject
    lateinit var cloudJobScheduler: com.eva.cloudtranscribe.work.CloudJobScheduler

    @Inject
    lateinit var statusTracker: com.eva.recorder.domain.RecorderStatusTracker

    private val binder = LocalBinder()
    private val _bookMarks = MutableStateFlow(emptySet<LocalTime>())
    private val _warnings = Channel<String>(Channel.BUFFERED)


    inner class LocalBinder : Binder(), RecorderDataInterface {

        override val recorderTime: Flow<LocalTime>
            get() = voiceRecorder.recorderTimer.sample(100.milliseconds)

        override val recorderState: StateFlow<RecorderState>
            get() = voiceRecorder.recorderState

        override val amplitudes: Flow<List<RecordedPoint>>
            get() = voiceRecorder.dataPoints

        override val bookMarks: Flow<Set<Duration>>
            get() = _bookMarks.mapLatest { bookMarks ->
                // convert it to set such that common items are subtracted
                bookMarks.map(LocalTime::toDuration).toSet()
            }

        override val transcript: Flow<com.eva.recorder.domain.recorder.LiveTranscriptState>
            get() = voiceRecorder.transcription

        override val liveCloud: Flow<com.eva.cloudtranscribe.live.LiveCloudState>
            get() = this@VoiceRecorderService.liveCloud.state

        override val warnings: Flow<String>
            get() = _warnings.receiveAsFlow()
    }

    /**
     * Recorder timer sampled per 1 seconds
     */
    private val widgetTimer: Flow<LocalTime>
        get() = voiceRecorder.recorderTimer.sample(1.seconds)

    /**
     * Notification timer check the seconds params
     */
    private val notificationTimer: Flow<LocalTime>
        get() = voiceRecorder.recorderTimer
            .distinctUntilChanged { old, new -> old.second == new.second }

    private val sessionCoordinator by lazy {
        RecorderSessionCoordinator(
            recorder = voiceRecorder,
            transcriber = voiceRecorder,
            liveCloud = liveCloud,
            transcriptStore = transcriptStore,
        )
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.d(TAG, "SERVICE BOUNDED")
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // check use case
            lifecycleScope.launch {
                bluetoothScoUseCase.startConnectionIfAllowed()
            }
            // read phone states
            observeChangingPhoneState()
            // inform bt connect
            showBluetoothConnectedToast()
            // update the notification
            readTimerAndUpdateNotification()
            // update widget state
            updateRecorderWidgetState()
            // mirror state for the quick settings tile
            publishRecorderStatus()
            // initiate transcript
            lifecycleScope.launch {
                voiceRecorder.initTranscriptions()
            }
            // live cloud is started per recording in onStartRecording (the service instance can outlive a recording
            // while the UI is bound, so starting it only here left later recordings without cloud and with stale UI)
            Log.i(TAG, "SERVICE CREATED WITH OBSERVERS")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            RecorderAction.StartRecorderAction.action -> onStartRecording()
            RecorderAction.ResumeRecorderAction.action -> onResumeRecording()
            RecorderAction.PauseRecorderAction.action -> onPauseRecording()
            RecorderAction.StopRecorderAction.action -> onStopRecording()
            RecorderAction.CancelRecorderAction.action -> onCancelRecording()
            RecorderAction.AddBookMarkAction.action -> addBookMark()
        }
        // not sticky so if there is a process killed android don't recreate this service
        // thus there is no null intent delivered
        return START_NOT_STICKY
    }


    private fun showBluetoothConnectedToast() = lifecycleScope.launch {
        bluetoothScoUseCase.observeConnectedState(onStateConnected = ::showScoConnectToast)
    }

    private fun observeChangingPhoneState() {
        voiceRecorder.recorderState.onEach {
            phoneStateObserverUseCase.checkIfAllowedAndRinging(
                isRecording = it == RecorderState.RECORDING,
                onPhoneRinging = {
                    phoneRingingToast()
                    onPauseRecording()
                },
            )

        }.launchIn(lifecycleScope)
    }


    private fun readTimerAndUpdateNotification() {
        combine(notificationTimer, voiceRecorder.recorderState) { time, state ->
            when (state) {
                RecorderState.RECORDING -> notificationHelper.showNotificationDuringRecording(time)
                RecorderState.COMPLETED -> notificationHelper.showRecordingDoneNotification()
                else -> {}
            }
        }.launchIn(lifecycleScope)
    }

    private fun updateRecorderWidgetState() {
        combine(widgetTimer, voiceRecorder.recorderState) { time, state ->
            // update the widget
            widgetFacade.updateWidget(state, time)
        }.launchIn(lifecycleScope)
    }

    private fun publishRecorderStatus() {
        voiceRecorder.recorderState.onEach { state ->
            statusTracker.publish(state)
            com.eva.recorder.data.tile.RecorderTileService.requestRefresh(applicationContext)
        }.launchIn(lifecycleScope)
    }

    private fun onStartRecording() {
        // set the foreground first before starting
        startVoiceRecorderService(
            NotificationConstants.RECORDER_NOTIFICATION_ID,
            notificationHelper.timerNotification,
        )
        // start the recorder
        lifecycleScope.launch {
            val started = sessionCoordinator.startRecording()
            if (started) {
                startVoiceRecorderService(
                    NotificationConstants.RECORDER_NOTIFICATION_ID,
                    notificationHelper.timerNotification,
                )
            }
        }.invokeOnCompletion {
            if (sessionCoordinator.hasPendingStart) return@invokeOnCompletion
            if (voiceRecorder.recorderState.value.let { it == RecorderState.RECORDING || it == RecorderState.PAUSED || it == RecorderState.PREPARING }) return@invokeOnCompletion
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // just a log.
        Log.d(TAG, "SERVICE UN_BOUNDED")
        return super.onUnbind(intent)
    }

    private fun onResumeRecording() {
        lifecycleScope.launch {
            sessionCoordinator.resumeRecording(
                onResumed = { notificationHelper.setOnResumeNotification() },
            )
        }
    }

    private fun onPauseRecording() {
        lifecycleScope.launch {
            sessionCoordinator.pauseRecording(
                onPaused = { notificationHelper.setOnPauseNotification() },
            )
        }
    }

    private fun onCancelRecording() {
        lifecycleScope.launch {
            sessionCoordinator.cancelRecording(
                onCancelled = {
                    // clear bookmarks
                    clearBookMarks()
                    // update widget
                    widgetFacade.resetWidget()
                },
            )
        }.invokeOnCompletion {
            if (sessionCoordinator.hasPendingStart) return@invokeOnCompletion
            // a newer recording may have started in this same instance while this one was finishing: don't kill it
            if (voiceRecorder.recorderState.value.let { it == RecorderState.RECORDING || it == RecorderState.PAUSED || it == RecorderState.PREPARING }) return@invokeOnCompletion
            // stop the foreground
            stopForeground(STOP_FOREGROUND_REMOVE)
            // stop the service
            stopSelf()
        }
    }

    private fun onStopRecording() {
        lifecycleScope.launch {
            sessionCoordinator.stopRecording(
                onRecordingSaved = { recordingId, timeBeforeSave ->
                    clearAndSaveBookMarks(recordingId, timeBeforeSave)
                    recordingAutoUploader.enqueue(recordingId)
                    // opt-in only (default off); never reached for cancelled recordings
                    cloudJobScheduler.onRecordingFinalized(recordingId)
                    // again show the notification
                    notificationHelper.showCompletedNotificationWithIntent(recordingId)
                },
                onSaveFailed = { error ->
                    val message = error.message ?: ""
                    showSaveRecordingErrorMessage(message)
                },
                onResetUi = {
                    widgetFacade.resetWidget()
                },
            )
        }.invokeOnCompletion {
            if (sessionCoordinator.hasPendingStart) return@invokeOnCompletion
            if (voiceRecorder.recorderState.value.let { it == RecorderState.RECORDING || it == RecorderState.PAUSED || it == RecorderState.PREPARING }) return@invokeOnCompletion
            // stop the foreground
            stopForeground(STOP_FOREGROUND_REMOVE)
            // stop the service
            stopSelf()
        }
    }

    private fun addBookMark() {

        val timeWhenClicked = voiceRecorder.recorderTimer.value
        val closestSecond = timeWhenClicked.roundToClosestSeconds()
        val lastBookMark = _bookMarks.value.lastOrNull()

        if (lastBookMark != null && timeWhenClicked.toMillisecondOfDay() - lastBookMark.toMillisecondOfDay() < 500) {
            Log.w(TAG, "BOOKMARKING TOO CLOSE CLIPS IS NOT ALLOWED")
            val message = applicationContext.getString(R.string.bookmarks_too_fast_warning)
            _warnings.trySend(message)
            return
        }

        if (closestSecond in _bookMarks.value) {
            val message = applicationContext.getString(R.string.bookmarks_too_fast_warning)
            _warnings.trySend(message)
            return
        }

        Log.d(TAG, "BOOKMARKS ADDED :$closestSecond")
        _bookMarks.update { it + closestSecond }
    }

    private suspend fun clearAndSaveBookMarks(recordingId: Long, lastRecordedTime: LocalTime) {
        // no need to perform any actions if bookmarks is empty
        if (_bookMarks.value.isEmpty()) return
        // add the coroutines on a different coroutine
        val job = lifecycleScope.launch {
            // filter the bookmarks
            val bookmarks = _bookMarks.value
                .filter { it <= lastRecordedTime }
                .toSet()

            Log.d(TAG, "SAVING ${bookmarks.size} BOOKMARKS ")

            val result = bookmarksProvider.createBookMarks(
                recordingId = recordingId,
                points = bookmarks,
            )
            when (result) {
                is Resource.Error -> {
                    val message = result.message ?: result.error.message ?: "ERROR"
                    Log.wtf(TAG, message)
                }

                is Resource.Success -> showBookmarksSavedMessage()
                else -> {}
            }
        }
        Log.d(TAG, "BOOKMARKS SAVED")
        // add logic to save bookmarks
        Log.d(TAG, "BOOKMARKS CLEARED")
        _bookMarks.update { emptySet() }
        // waits for the job completion
        job.join()
    }

    private fun clearBookMarks() {
        Log.d(TAG, "BOOKMARKS CLEARED")
        _bookMarks.update { emptySet() }
    }

    override fun onDestroy() {
        // close sco connection
        bluetoothScoUseCase.closeConnectionIfPresent()
        // resources are cleared
        voiceRecorder.releaseResources()
        // release transcript
        voiceRecorder.transcriptCleanUp()
        liveCloud.release()
        // the service (and any session) is gone: the tile must not stay ACTIVE
        statusTracker.publish(RecorderState.IDLE)
        com.eva.recorder.data.tile.RecorderTileService.requestRefresh(applicationContext)
        Log.i(TAG, "RECORDER SERVICE DESTROYED")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VOICE_RECORDER_SERVICE"
    }
}
