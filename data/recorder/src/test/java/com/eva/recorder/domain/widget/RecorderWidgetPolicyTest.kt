package com.eva.recorder.domain.widget

import com.eva.recorder.domain.models.RecorderState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderWidgetPolicyTest {

	@Test
	fun idle_like_states_start_a_recording() {
		listOf(RecorderState.IDLE, RecorderState.COMPLETED, RecorderState.CANCELLED).forEach {
			assertTrue(it.name, RecorderWidgetPolicy.shouldStartOnTap(it))
		}
	}

	@Test
	fun active_states_only_open_the_recorder() {
		listOf(RecorderState.RECORDING, RecorderState.PAUSED, RecorderState.PREPARING).forEach {
			assertFalse(it.name, RecorderWidgetPolicy.shouldStartOnTap(it))
		}
	}
}
