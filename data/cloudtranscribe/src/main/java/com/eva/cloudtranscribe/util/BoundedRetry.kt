package com.eva.cloudtranscribe.util

/** Returns permanent outcomes immediately; cancellation and exceptions propagate to the caller. */
internal suspend fun <T> boundedRetry(
	attempts: Int,
	request: suspend () -> T,
	shouldRetry: (T) -> Boolean,
	pause: suspend (Long) -> Unit,
): T {
	require(attempts > 0)
	for (attempt in 0 until attempts) {
		val result = request()
		if (!shouldRetry(result) || attempt == attempts - 1) return result
		pause(2000L * (attempt + 1))
	}
	error("Unreachable")
}
