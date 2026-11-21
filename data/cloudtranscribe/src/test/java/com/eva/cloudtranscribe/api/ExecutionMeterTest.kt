package com.eva.cloudtranscribe.api

import com.eva.cloudtranscribe.model.TranscriptionExecution
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionMeterTest {
	@Test fun countsMissingPricesFailuresAndExplicitZero() {
		var now = 10L
		val responses = listOf(null, "invalid", "0", "0.01234", "3e-5", "-1").iterator()
		val meter = ExecutionMeter(HttpTransport { _, _, _, _ ->
			if (!responses.hasNext()) throw IOException("offline")
			HttpResponse(429, "", responses.next())
		}) { now }
		assertEquals(null, meter.snapshot().knownUsd)
		repeat(7) { runCatching { meter.postJson("", emptyMap(), "", 1) } }
		now += 2_500_000_000
		assertEquals(TranscriptionExecution(2500, 7, 3, "0.01237"), meter.snapshot())
	}

	@Test fun hourlyRateRequiresCompleteCoverageAndFullAudio() {
		val full = TranscriptionExecution(100, 2, 2, "0.02", 600_000)
		assertEquals("0.12", full.usdPerAudioHour())
		assertEquals(null, full.copy(pricedRequests = 1).usdPerAudioHour())
		assertEquals(null, full.copy(fullAudioMs = null).usdPerAudioHour())
	}

	@Test fun zeroIsReportedAsZero() {
		val meter = ExecutionMeter(HttpTransport { _, _, _, _ -> HttpResponse(200, "", "0.000") })
		meter.postJson("", emptyMap(), "", 1)
		assertEquals("0", meter.snapshot().knownUsd)
	}
}
