package com.eva.cloudtranscribe.slack

/** Server rate-limit deadline, persisted by the caller and rechecked before each network attempt. */
internal class SlackRetryGate(
	private val readDeadline: () -> Long,
	private val writeDeadline: (Long) -> Unit,
	private val clock: () -> Long = { System.currentTimeMillis() },
) {
	fun canSend(): Boolean = clock() >= readDeadline()

	fun defer(seconds: Long) {
		val now = clock()
		val delay = seconds.coerceAtLeast(0).coerceAtMost((Long.MAX_VALUE - now) / 1_000) * 1_000
		writeDeadline(maxOf(readDeadline(), now + delay))
	}
}
