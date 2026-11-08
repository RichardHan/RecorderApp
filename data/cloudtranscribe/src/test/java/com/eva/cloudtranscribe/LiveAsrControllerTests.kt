package com.eva.cloudtranscribe

import com.eva.transcribe.offline.LiveAsrController
import com.eva.transcribe.offline.LiveAsrRun
import com.eva.transcribe.offline.LiveAsrRunResult
import com.eva.transcribe.offline.LiveAsrStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAsrControllerTests {
	private class FakeRun(val tag: String) : LiveAsrRun {
		val l = MutableStateFlow<List<String>>(emptyList())
		override val lines: StateFlow<List<String>> = l
		override val timedLines: StateFlow<List<Pair<Long, String>>> = MutableStateFlow(emptyList())
		override val decoding: StateFlow<Boolean> = MutableStateFlow(false)
		override val warning: StateFlow<String?> = MutableStateFlow(null)
		var finished = false; var cancelled = false
		override suspend fun finish(): LiveAsrRunResult { finished = true; l.value = l.value + "$tag-tail"; return LiveAsrRunResult(1, 0, 0, true) }
		override fun cancel() { cancelled = true }
	}

	@Test fun offWithoutModelNeverStarts() = runTest {
		var starts = 0
		val c = LiveAsrController(backgroundScope, { starts++; Result.success(FakeRun("x")) })
		c.apply(false, "m"); assertEquals(LiveAsrStatus.OFF, c.state.value.status)
		c.apply(true, null); assertEquals(LiveAsrStatus.NO_MODEL, c.state.value.status)
		assertEquals(0, starts)
		assertNull(c.finishAll())
	}

	@Test fun toggleOffKeepsTextFlushesAndReleasesThenOnStartsFresh() = runTest {
		val runs = mutableListOf<FakeRun>(); var releases = 0
		val c = LiveAsrController(backgroundScope, { m -> Result.success(FakeRun("r${runs.size}").also { runs += it }) }, { releases++ })
		c.apply(true, "m"); runs[0].l.value = listOf("第一句"); advanceUntilIdle(); runCurrent()
		assertEquals(listOf("第一句"), c.state.value.lines)
		c.apply(false, "m")
		assertTrue(runs[0].finished); assertEquals(1, releases)
		assertEquals(LiveAsrStatus.OFF, c.state.value.status)
		assertEquals(listOf("第一句", "r0-tail"), c.state.value.lines)
		c.apply(true, "m"); runs[1].l.value = listOf("第二句"); advanceUntilIdle(); runCurrent()
		assertEquals(listOf("第一句", "r0-tail", "第二句"), c.state.value.lines)
		val agg = c.finishAll()!!
		assertTrue(agg.hadGaps) // provider marks the saved transcript PARTIAL when hadGaps
		assertEquals("第一句\nr0-tail\n第二句\nr1-tail", agg.text)
	}

	@Test fun rapidTogglingNeverOverlapsRuns() = runTest {
		var active = 0; var maxActive = 0
		val c = LiveAsrController(backgroundScope, {
			active++; maxActive = maxOf(maxActive, active)
			Result.success(object : LiveAsrRun by FakeRun("f") {
				override suspend fun finish(): LiveAsrRunResult { active--; return LiveAsrRunResult(0, 0, 0, true) }
			})
		})
		val jobs = (0 until 20).map { i -> async { c.apply(i % 2 == 0, "m") } }
		jobs.forEach { it.await() }
		assertEquals(1, maxActive)
		c.finishAll(); assertEquals(0, active)
	}

	@Test fun sameStateIsIdempotent() = runTest {
		var starts = 0
		val c = LiveAsrController(backgroundScope, { starts++; Result.success(FakeRun("x")) })
		c.apply(true, "m"); c.apply(true, "m"); assertEquals(1, starts)
		c.apply(true, "m2"); assertEquals(2, starts) // model switch restarts
	}

	@Test fun loadFailureShowsErrorAndNothingRuns() = runTest {
		val c = LiveAsrController(backgroundScope, { Result.failure(IllegalStateException("OOM")) })
		c.apply(true, "m")
		assertEquals(LiveAsrStatus.ERROR, c.state.value.status)
		assertTrue(c.state.value.message!!.contains("OOM"))
		assertNull(c.finishAll())
	}

	@Test fun stopFlushesTailAndIgnoresLaterToggles() = runTest {
		val run = FakeRun("r"); var starts = 0
		val c = LiveAsrController(backgroundScope, { starts++; Result.success(run) })
		c.apply(true, "m")
		val agg = c.finishAll()!!
		assertTrue(run.finished); assertEquals("r-tail", agg.text); assertTrue(agg.complete)
		c.apply(true, "m"); assertEquals(1, starts)
	}

	@Test fun cancelDropsRun() = runTest {
		val run = FakeRun("r")
		val c = LiveAsrController(backgroundScope, { Result.success(run) })
		c.apply(true, "m"); c.cancelAll()
		assertTrue(run.cancelled); assertNull(c.finishAll())
	}

	// regression (t86u): service instance reused for recording B after A stopped — A's text must not leak into B
	@Test fun newRecordingAfterFinishStartsCleanAndIgnoresLateOldCallbacks() = runTest {
		val runs = mutableListOf<FakeRun>()
		val c = LiveAsrController(backgroundScope, { Result.success(FakeRun("r${runs.size}").also { runs += it }) })
		c.apply(true, "m"); runs[0].l.value = listOf("三月考核時，根據平時。"); advanceUntilIdle(); runCurrent()
		val a = c.finishAll()!!
		assertTrue(a.text.contains("三月考核"))
		// before the fix: controller stayed closed, apply() was ignored and the old line stayed on screen
		c.apply(true, "m"); assertEquals(1, runs.size)
		c.resetForNewRecording()
		assertTrue(c.state.value.lines.isEmpty())
		c.apply(true, "m"); assertEquals(2, runs.size)
		runs[0].l.value = listOf("A 的遲到回呼") // late callback from the old run
		runs[1].l.value = listOf("B 第一句"); advanceUntilIdle(); runCurrent()
		assertEquals(listOf("B 第一句"), c.state.value.lines)
		val b = c.finishAll()!!
		assertFalse(b.text.contains("三月考核")); assertFalse(b.text.contains("A 的"))
		assertFalse(b.hadGaps)
	}

	@Test fun resetDuringActiveRunCancelsItWithoutSaving() = runTest {
		val runs = mutableListOf<FakeRun>(); var releases = 0
		val c = LiveAsrController(backgroundScope, { Result.success(FakeRun("r${runs.size}").also { runs += it }) }, { releases++ })
		c.apply(true, "m"); runs[0].l.value = listOf("舊"); advanceUntilIdle(); runCurrent()
		c.resetForNewRecording()
		assertTrue(runs[0].cancelled); assertEquals(1, releases)
		assertTrue(c.state.value.lines.isEmpty()); assertNull(c.finishAll())
	}
}

class LiveCloudOwnershipTests {
	private val O = com.eva.cloudtranscribe.live.LiveCloudOwnership
	@Test fun onlyCurrentOrStillFinishingSessionPublishes() {
		val a = Any(); val b = Any()
		assertTrue(O.mayPublish(a, a, null))
		assertTrue(O.mayPublish(a, null, a))   // A stopped and flushing, no new recording yet: final key points still shown
		assertFalse(O.mayPublish(a, b, a))     // B started: late A state can never overwrite B's UI
		assertTrue(O.mayPublish(b, b, a))
		assertFalse(O.mayPublish(a, null, null))
	}
}
