package com.eva.cloudtranscribe.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BoundedRetryTests {
	@Test fun `permanent outcome is not retried or delayed`() = runBlocking {
		var calls = 0
		val result = boundedRetry(3, { calls++; "permanent" }, { false }, { error("Must not delay") })
		assertEquals("permanent", result)
		assertEquals(1, calls)
	}
	@Test fun `temporary outcomes retry with bounded backoff but no final delay`() = runBlocking {
		var calls = 0
		val delays = mutableListOf<Long>()
		boundedRetry(3, { ++calls }, { true }, { delays.add(it) })
		assertEquals(3, calls)
		assertEquals(listOf(2000L, 4000L), delays)
	}
	@Test fun `success after temporary error stops further calls`() = runBlocking {
		var calls = 0
		val result = boundedRetry(3, { ++calls }, { it < 2 }, {})
		assertEquals(2, result)
		assertEquals(2, calls)
	}
	@Test fun `cancellation propagates without another request`() {
		var calls = 0
		assertThrows(CancellationException::class.java) {
			runBlocking { boundedRetry(3, { calls++; throw CancellationException() }, { true }, {}) }
		}
		assertEquals(1, calls)
	}
	@Test fun `invalid attempt count makes no requests`() {
		assertThrows(IllegalArgumentException::class.java) {
			runBlocking { boundedRetry(0, { error("Must not request") }, { true }, {}) }
		}
	}
}
