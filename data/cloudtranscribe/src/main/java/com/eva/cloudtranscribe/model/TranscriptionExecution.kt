package com.eva.cloudtranscribe.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable

/** Latest worker invocation. Resuming a transcript starts another measurement. */
@Serializable
data class TranscriptionExecution(
	val elapsedMs: Long,
	val requests: Int,
	val pricedRequests: Int,
	val knownUsd: String?,
	val fullAudioMs: Long? = null,
) {
	fun usdPerAudioHour(): String? {
		val duration = fullAudioMs?.takeIf { it > 0 } ?: return null
		if (pricedRequests == 0 || pricedRequests != requests) return null
		return knownUsd?.toBigDecimalOrNull()?.multiply(BigDecimal.valueOf(3_600_000))
			?.divide(BigDecimal.valueOf(duration), 6, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
	}
}
