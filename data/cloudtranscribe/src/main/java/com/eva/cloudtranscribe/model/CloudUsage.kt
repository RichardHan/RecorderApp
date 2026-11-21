package com.eva.cloudtranscribe.model

import com.eva.cloudtranscribe.usage.CostEstimate
import com.eva.cloudtranscribe.usage.PeriodUsage
import com.eva.cloudtranscribe.usage.TokenPricing
import com.eva.cloudtranscribe.usage.TokenUsageEvent
import com.eva.cloudtranscribe.usage.ModelPrice
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class MonthUsage(
	val month: YearMonth,
	val cloudTranscripts: Int = 0,
	val uploadedMs: Long = 0,
	val requests: Int = 0,
	val summaries: Int = 0,
	val promptTokens: Long = 0,
	val completionTokens: Long = 0,
	val tokenRequests: Int = 0,
	val cost: CostEstimate = CostEstimate(),
) {
	val isEmpty: Boolean
		get() = cloudTranscripts == 0 && summaries == 0 && requests == 0 &&
			promptTokens == 0L && completionTokens == 0L && tokenRequests == 0
}

/**
 * Local, read-only usage estimate from stored records (spec: market-cloud-usage). Not a bill: deleted recordings and
 * manual features (chapters, Q&A, translation, live key points) are not recorded in the request/minute counters.
 * Token totals come from a separate on-device log of actual API `usage` fields when present.
 */
object CloudUsage {

	const val MONTHS = 6

	/** Duration of pieces that were actually sent (attempts > 0); split segments count their sub-parts instead. */
	fun uploadedMs(record: TranscriptRecord): Long =
		record.segments.flatMap { s -> if (s.parts.isNotEmpty()) s.parts else listOf(s) }
			.filter { it.attempts > 0 && it.endMs > it.startMs }
			.sumOf { it.endMs - it.startMs }

	fun monthOf(millis: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochMilli(millis).atZone(zone))

	fun summarize(
		transcripts: List<TranscriptRecord>,
		summaries: List<SummaryRecord>,
		zone: ZoneId,
		nowMillis: Long,
		months: Int = MONTHS,
		titleRequestAtMillis: List<Long> = emptyList(),
		tokenEvents: List<TokenUsageEvent> = emptyList(),
		prices: List<ModelPrice> = emptyList(),
	): List<MonthUsage> {
		val current = monthOf(nowMillis, zone)
		val wanted = (0 until months).map { current.minusMonths(it.toLong()) }
		val byMonth = wanted.associateWith { MonthUsage(it) }.toMutableMap()
		transcripts.filter { it.source == TranscriptSource.CLOUD }.forEach { t ->
			val m = monthOf(t.createdAtMillis, zone)
			byMonth[m]?.let { u ->
				byMonth[m] = u.copy(cloudTranscripts = u.cloudTranscripts + 1, uploadedMs = u.uploadedMs + uploadedMs(t), requests = u.requests + t.requestCount)
			}
		}
		summaries.forEach { s ->
			val m = monthOf(s.createdAtMillis, zone)
			byMonth[m]?.let { u -> byMonth[m] = u.copy(summaries = u.summaries + 1) }
		}
		titleRequestAtMillis.forEach { at ->
			val m = monthOf(at, zone)
			byMonth[m]?.let { u -> byMonth[m] = u.copy(requests = u.requests + 1) }
		}
		tokenEvents.forEach { event ->
			val m = monthOf(event.atMillis, zone)
			byMonth[m]?.let { u ->
				byMonth[m] = u.copy(
					promptTokens = u.promptTokens + event.promptTokens,
					completionTokens = u.completionTokens + event.completionTokens,
					tokenRequests = u.tokenRequests + 1,
				)
			}
		}
		return wanted.map { month ->
			val usage = byMonth.getValue(month)
			val monthEvents = tokenEvents.filter { monthOf(it.atMillis, zone) == month }
			if (monthEvents.isEmpty()) usage else usage.copy(cost = TokenPricing.estimate(monthEvents, prices))
		}
	}

	fun visibleMonths(usage: List<MonthUsage>): List<MonthUsage> = usage.filterNot { it.isEmpty }

	fun lastDays(
		tokenEvents: List<TokenUsageEvent>,
		prices: List<ModelPrice>,
		nowMillis: Long,
		days: Int = 30,
	): PeriodUsage {
		val start = nowMillis - days * 86_400_000L
		return TokenPricing.period(tokenEvents.filter { it.atMillis in start until nowMillis }, prices)
	}

	fun forRecording(recordingId: Long, tokenEvents: List<TokenUsageEvent>, prices: List<ModelPrice>): PeriodUsage =
		TokenPricing.period(tokenEvents.filter { it.recordingId == recordingId }, prices, recordingId)

	fun startOfMonthMillis(month: YearMonth, zone: ZoneId): Long =
		month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

	fun minutes(ms: Long): String = "%.1f".format(ms / 60_000.0)
}
