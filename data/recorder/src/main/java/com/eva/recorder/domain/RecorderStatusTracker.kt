package com.eva.recorder.domain

import com.eva.recorder.domain.models.RecorderState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide mirror of the recorder service state, so surfaces that do not bind to the service
 * (Quick Settings tile, a deep-link start request) read the same source as the service itself.
 *
 * Only [com.eva.recorder.data.service.VoiceRecorderService] publishes. When the process dies the
 * service dies with it, so the default [RecorderState.IDLE] is also the correct value after restart.
 */
@Singleton
class RecorderStatusTracker @Inject constructor() {

	private val _state = MutableStateFlow(RecorderState.IDLE)
	val state: StateFlow<RecorderState> = _state.asStateFlow()

	internal fun publish(state: RecorderState) {
		_state.value = state
	}

	/** True when a new recording can be started (nothing is preparing, recording or paused). */
	val canStartNewRecording: Boolean
		get() = com.eva.recorder.domain.tile.RecorderTilePolicy.actionFor(_state.value) == com.eva.recorder.domain.tile.RecorderTileClick.OPEN_APP_TO_START
}
