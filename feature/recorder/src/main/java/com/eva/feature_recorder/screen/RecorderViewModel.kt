package com.eva.feature_recorder.screen

import androidx.lifecycle.viewModelScope
import com.eva.datastore.domain.models.TranscriptionSettings
import com.eva.datastore.domain.repository.TranscriptionSettingsRepo
import com.eva.recorder.domain.recorder.LiveTranscriptState
import com.eva.recorder.domain.RecorderActionHandler
import com.eva.recorder.domain.RecorderServiceBinder
import com.eva.recorder.domain.models.RecorderAction
import com.eva.recorder.domain.models.RecorderState
import com.eva.ui.viewmodel.AppViewModel
import com.eva.ui.viewmodel.UIEvents
import com.eva.utils.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime

@HiltViewModel
internal class RecorderViewModel @Inject constructor(
    private val handler: RecorderActionHandler,
    private val recorderService: RecorderServiceBinder,
    private val transcriptionSettings: TranscriptionSettingsRepo,
) : AppViewModel() {

    /** Live on-device captions on/off; only this preference changes (cloud settings are independent). */
    fun setLiveTranscription(enabled: Boolean) {
        viewModelScope.launch { transcriptionSettings.onEnableOrDisable(enabled) }
    }

    private val _uiEvents = MutableSharedFlow<UIEvents>()
    override val uiEvent: SharedFlow<UIEvents>
        get() = _uiEvents.onStart { observeRecordingWarnings() }
            .shareIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000L), replay = 1)

    val isServiceReady: StateFlow<Boolean> = recorderService.isConnectionReady

    val recorderState = recorderService.recorderState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(10_000),
            initialValue = RecorderState.IDLE,
        )

    val recorderTime = recorderService.recorderTimer
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(10_000),
            initialValue = LocalTime(0, 0),
        )

    val bookMarksSet = recorderService.bookMarkTimes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(10_000),
            initialValue = emptySet(),
        )

    val recordingPoints = recorderService.amplitudes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(10_000),
            initialValue = emptyList(),
        )


    /** Full live transcript (all finalized lines + partial + status); never truncated or auto-cleared. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transcriptionsResult: StateFlow<LiveTranscriptState> = recorderService.recorderState
        .flatMapLatest { state ->
            if (!state.canReadAmplitudes) flowOf(LiveTranscriptState())
            else recorderService.transcriptions
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LiveTranscriptState(),
        )

    /** Advanced live cloud state; stays inactive (and no requests happen) unless the user opted in. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveCloud: StateFlow<com.eva.cloudtranscribe.live.LiveCloudState> = recorderService.recorderState
        .flatMapLatest { state ->
            if (!state.canReadAmplitudes) flowOf(com.eva.cloudtranscribe.live.LiveCloudState())
            else recorderService.liveCloud
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.eva.cloudtranscribe.live.LiveCloudState())

    val transcribeSettings = transcriptionSettings.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = TranscriptionSettings(),
        )

    fun onAction(action: RecorderAction) {

        when (val resource = handler.onRecorderAction(action)) {
            is Resource.Error -> viewModelScope.launch {
                val message = resource.error.message ?: resource.message ?: ""
                _uiEvents.emit(UIEvents.ShowToast(message))
            }

            else -> {}
        }
    }


    fun onEvent(event: RecorderScreenEvent) {
        when (event) {
            RecorderScreenEvent.BindRecorderService -> recorderService.bindToService()
            RecorderScreenEvent.UnBindRecorderService -> recorderService.unBindService()
        }
    }

    private fun observeRecordingWarnings() {
        // observe the recording warnings and emit it on a toast
        recorderService.recorderWarnings()
            .onEach { _uiEvents.emit(UIEvents.ShowToast(it)) }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        recorderService.unBindService()
        recorderService.cleanUp()
    }
}
