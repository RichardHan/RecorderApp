package com.eva.cloudtranscribe.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class MonthUsage(
	val month: YearMonth,
	val cloudTranscripts: Int = 0,
	val uploadedMs: Long = 0,
	val requests: Int = 0,
	val summaries: Int = 0,
)

/**
 * Local, read-only usage estimate from stored records (spec: market-cloud-usage). Not a bill: deleted recordings and
 * manual features (chapters, Q&A, translation, live key points) are not recorded here.
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
		return wanted.map { byMonth.getValue(it) }
	}

	fun minutes(ms: Long): String = "%.1f".format(ms / 60_000.0)
}
