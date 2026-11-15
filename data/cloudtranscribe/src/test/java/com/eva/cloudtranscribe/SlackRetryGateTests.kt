package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.slack.SlackRetryGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlackRetryGateTests {
	@Test fun serverWaitLongerThanAnHourIsNotShortened() {
		org.junit.Assert.assertEquals(
			com.eva.cloudtranscribe.slack.SlackSendResult.RetryAfter(7200),
			com.eva.cloudtranscribe.slack.SlackSendPolicy.classify(429, "", "7200"),
		)
	}
	@Test fun longRetryAfterSurvivesWorkerRecreationAndEarlyBackoffWakeups() {
		var now = 1_000L
		var persisted = 0L
		fun newWorkerGate() = SlackRetryGate({ persisted }, { persisted = it }, { now })
		newWorkerGate().defer(300)
		now += 30_000
		assertFalse(newWorkerGate().canSend())
		now = 300_999
		assertFalse(newWorkerGate().canSend())
		now = 301_000
		assertTrue(newWorkerGate().canSend())
	}

	@Test fun shorterResponseCannotShortenExistingCooldown() {
		var now = 10_000L
		var persisted = 0L
		val gate = SlackRetryGate({ persisted }, { persisted = it }, { now })
		gate.defer(300)
		now += 1_000
		gate.defer(30)
		now += 30_000
		assertFalse(gate.canSend())
		now = 310_000
		assertTrue(gate.canSend())
	}
}
