package com.eva.cloudtranscribe.usage

import java.util.Locale
import kotlinx.serialization.Serializable

/** Tokens reported by one successful provider response. Missing fields stay 0. */
@Serializable
data class TokenUsage(
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val totalTokens: Long? = null,
) {
	val hasCounts: Boolean get() = promptTokens > 0 || completionTokens > 0 || (totalTokens ?: 0) > 0
	val billedTokens: Long get() = if (totalTokens != null && totalTokens > 0) totalTokens else promptTokens + completionTokens
}

/** One locally persisted, billable API response. Never contains secrets or payloads. */
@Serializable
data class TokenUsageEvent(
	val atMillis: Long,
	val model: String,
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val totalTokens: Long? = null,
	val recordingId: Long? = null,
	val provider: String? = null,
	val operation: String? = null,
) {
	val billedTokens: Long get() = totalTokens?.takeIf { it > 0 } ?: (promptTokens + completionTokens)
}

/** Optional per-model unit prices entered by the user. Empty strings mean unset. */
@Serializable
data class ModelPrice(
	val model: String,
	val inputPerMillion: String = "",
	val outputPerMillion: String = "",
)

data class CostEstimate(
	val amount: Double? = null,
	val pricedTokens: Long = 0,
	val unpricedTokens: Long = 0,
) {
	val hasUnsetPrice: Boolean get() = unpricedTokens > 0
	val canShowAmount: Boolean get() = amount != null
}

data class PeriodUsage(
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val tokenRequests: Int = 0,
	val cost: CostEstimate = CostEstimate(),
	val recordingId: Long? = null,
)

object TokenPricing {
	const val PER_MILLION = 1_000_000.0

	fun parseUnitPrice(raw: String?): Double? {
		val t = raw?.trim()?.replace(",", "").orEmpty()
		if (t.isEmpty()) return null
		val value = t.toDoubleOrNull() ?: return null
		return value.takeIf { it >= 0.0 && it.isFinite() }
	}

	fun priceMap(prices: List<ModelPrice>): Map<String, ModelPrice> =
		prices.mapNotNull { p ->
			val name = p.model.trim()
			if (name.isEmpty()) null else name.lowercase(Locale.ROOT) to p
		}.toMap()

	fun estimate(events: Iterable<TokenUsageEvent>, prices: List<ModelPrice>): CostEstimate {
		val map = priceMap(prices)
		var amount = 0.0
		var priced = 0L
		var unpriced = 0L
		var anyPriced = false
		events.forEach { event ->
			val price = map[event.model.trim().lowercase(Locale.ROOT)]
			val input = parseUnitPrice(price?.inputPerMillion)
			val output = parseUnitPrice(price?.outputPerMillion)
			when {
				input != null && event.promptTokens > 0L -> {
					amount += event.promptTokens / PER_MILLION * input
					priced += event.promptTokens
					anyPriced = true
				}
				event.promptTokens > 0L -> unpriced += event.promptTokens
			}
			when {
				output != null && event.completionTokens > 0L -> {
					amount += event.completionTokens / PER_MILLION * output
					priced += event.completionTokens
					anyPriced = true
				}
				event.completionTokens > 0L -> unpriced += event.completionTokens
			}
			// If the provider only reported a total, and both sides are priced equally or only total exists
			if (event.promptTokens == 0L && event.completionTokens == 0L) {
				val total = event.billedTokens
				if (total > 0L) {
					when {
						input != null && output != null && input == output -> {
							amount += total / PER_MILLION * input
							priced += total
							anyPriced = true
						}
						else -> unpriced += total
					}
				}
			}
		}
		return CostEstimate(amount = if (anyPriced) amount else null, pricedTokens = priced, unpricedTokens = unpriced)
	}

	fun period(events: List<TokenUsageEvent>, prices: List<ModelPrice>, recordingId: Long? = null): PeriodUsage =
		PeriodUsage(
			promptTokens = events.sumOf { it.promptTokens },
			completionTokens = events.sumOf { it.completionTokens },
			tokenRequests = events.size,
			cost = estimate(events, prices),
			recordingId = recordingId,
		)

	fun formatTokens(count: Long): String = "%,d".format(Locale.US, count)

	fun formatCost(amount: Double): String = when {
		amount == 0.0 -> "0"
		amount < 0.01 -> String.format(Locale.US, "%.4f", amount)
		amount < 1.0 -> String.format(Locale.US, "%.3f", amount)
		else -> String.format(Locale.US, "%.2f", amount)
	}

	fun normalizePrices(prices: List<ModelPrice>, extraModels: List<String> = emptyList()): List<ModelPrice> {
		val byName = LinkedHashMap<String, ModelPrice>()
		(prices + extraModels.map { ModelPrice(it) }).forEach { p ->
			val name = p.model.trim()
			if (name.isEmpty()) return@forEach
			val key = name.lowercase(Locale.ROOT)
			val existing = byName[key]
			byName[key] = if (existing == null) p.copy(model = name) else existing.copy(
				model = existing.model.ifBlank { name },
				inputPerMillion = existing.inputPerMillion.ifBlank { p.inputPerMillion },
				outputPerMillion = existing.outputPerMillion.ifBlank { p.outputPerMillion },
			)
		}
		return byName.values.toList()
	}
}
