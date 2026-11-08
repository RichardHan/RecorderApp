package com.eva.recorder.domain

import com.eva.recorder.domain.models.RecordedPoint
import com.eva.recorder.domain.models.RecorderState
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalTime

interface RecorderServiceBinder {

    val isConnectionReady: StateFlow<Boolean>

    val recorderTimer: Flow<LocalTime>

    val recorderState: Flow<RecorderState>

    val bookMarkTimes: Flow<Set<Duration>>

    val amplitudes: Flow<List<RecordedPoint>>

    val transcriptions: Flow<com.eva.recorder.domain.recorder.LiveTranscriptState>

    /** Advanced live cloud transcript + key points (inactive state when the mode is off). */
    val liveCloud: Flow<com.eva.cloudtranscribe.live.LiveCloudState>

    fun recorderWarnings(): Flow<String> {
        return emptyFlow()
    }

    fun bindToService()

    fun unBindService()

    fun cleanUp()
}
