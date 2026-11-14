package com.eva.recorder.domain.tile

import com.eva.recorder.domain.RecorderStatusTracker
import com.eva.recorder.domain.models.RecorderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTilePolicyTest {

	@Test
	fun `idle like states open the app to start`() {
		listOf(RecorderState.IDLE, RecorderState.COMPLETED, RecorderState.CANCELLED).forEach {
			assertEquals(RecorderTileDisplay.IDLE, RecorderTilePolicy.displayFor(it))
			assertEquals(RecorderTileClick.OPEN_APP_TO_START, RecorderTilePolicy.actionFor(it))
		}
	}

	@Test
	fun `recording pauses and paused resumes, never stops`() {
		assertEquals(RecorderTileClick.PAUSE, RecorderTilePolicy.actionFor(RecorderState.RECORDING))
		assertEquals(RecorderTileClick.RESUME, RecorderTilePolicy.actionFor(RecorderState.PAUSED))
		assertEquals(RecorderTileDisplay.PAUSED, RecorderTilePolicy.displayFor(RecorderState.PAUSED))
		assertEquals(RecorderTileDisplay.RECORDING, RecorderTilePolicy.displayFor(RecorderState.RECORDING))
	}

	@Test
	fun `preparing ignores taps so no second session starts`() {
		assertEquals(RecorderTileClick.IGNORE, RecorderTilePolicy.actionFor(RecorderState.PREPARING))
	}

	@Test
	fun `debouncer drops rapid taps and accepts after the window`() {
		var now = 1_000L
		val debouncer = TileClickDebouncer(windowMillis = 800L, clock = { now })
		assertTrue(debouncer.tryAcquire())
		now += 100
		assertFalse(debouncer.tryAcquire())
		now += 699
		assertFalse(debouncer.tryAcquire())
		now += 1
		assertTrue(debouncer.tryAcquire())
	}

	@Test
	fun `start request gate consumes each id once`() {
		val gate = TileStartRequestGate()
		assertTrue(gate.consume(42L))
		assertFalse(gate.consume(42L))
		assertFalse(gate.consume(null))
		assertFalse(gate.consume(0L))
		assertTrue(gate.consume(43L))
	}

	@Test
	fun `restored gate keeps already handled ids`() {
		val gate = TileStartRequestGate(listOf(7L))
		assertFalse(gate.consume(7L))
		assertEquals(setOf(7L), gate.handledIds.toSet())
	}

	@Test
	fun `tracker mirrors published state and blocks double start`() {
		val tracker = RecorderStatusTracker()
		assertEquals(RecorderState.IDLE, tracker.state.value)
		assertTrue(tracker.canStartNewRecording)
		tracker.publish(RecorderState.PREPARING)
		assertFalse(tracker.canStartNewRecording)
		tracker.publish(RecorderState.RECORDING)
		assertFalse(tracker.canStartNewRecording)
		tracker.publish(RecorderState.PAUSED)
		assertFalse(tracker.canStartNewRecording)
		tracker.publish(RecorderState.COMPLETED)
		assertTrue(tracker.canStartNewRecording)
	}
}
