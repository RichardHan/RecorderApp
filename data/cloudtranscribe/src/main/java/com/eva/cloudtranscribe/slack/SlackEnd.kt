package com.eva.cloudtranscribe.slack

import com.eva.cloudtranscribe.engine.SpeakerOwnerPrompt
import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord

/** Snapshot the coordinator hands to the end-of-recording Slack worker. */
data class SlackEndRequest(
	val sessionId: String,
	val recordingId: Long,
	val durationMs: Long,
	val title: String?,
	val liveKeyPoints: List<String>,
	val maskSensitive: Boolean,
	val enqueuedAtMillis: Long,
)

/** Schedules one process-safe end message per recording session. */
interface SlackEndScheduler {
	fun schedule(request: SlackEndRequest)

	companion object {
		fun uniqueName(sessionId: String) = "slack_end_$sessionId"
	}
}

/** Parsed sections of a post-recording cloud summary (no invented names or content). */
data class SlackEndSections(
	val abstract: String? = null,
	val decisions: List<String> = emptyList(),
	val todos: List<String> = emptyList(),
	val points: List<String> = emptyList(),
) {
	val hasContent: Boolean
		get() = !abstract.isNullOrBlank() || decisions.isNotEmpty() || todos.isNotEmpty() || points.isNotEmpty()
}

/**
 * Pure end-message formatting. Speaker/owner text already in the summary is kept as-is;
 * this formatter never adds or guesses names.
 */
object SlackEndMessage {

	const val MAX_DECISIONS = 5
	const val MAX_TODOS = 5
	const val MAX_POINTS = 5
	const val NO_POINTS = LiveCloudSettings.NO_KEY_POINTS

	private val HEADING = Regex(
		"^(?:#{1,3}\\s*)?(摘要|決議|待辦事項|待辦|行動項目|行動|重點)[:：]?\\s*(.*)$",
	)
	private val SENTENCE = Regex("(?<=[。！？!?.])\\s*")

	fun durationLabel(durationMs: Long): String {
		val totalSec = (durationMs / 1_000L).coerceAtLeast(0L)
		val h = totalSec / 3_600L
		val m = (totalSec % 3_600L) / 60L
		val s = totalSec % 60L
		return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
	}

	fun header(title: String?, durationMs: Long?): String {
		val name = title?.trim().orEmpty().ifBlank { "錄音" }
		val dur = durationMs?.takeIf { it > 0 }?.let { "（${durationLabel(it)}）" }.orEmpty()
		return "✅ $name 已結束$dur"
	}

	fun format(
		title: String?,
		durationMs: Long?,
		abstract: String?,
		decisions: List<String>,
		todos: List<String>,
		points: List<String> = emptyList(),
	): String {
		val abs = abstract?.trim()?.takeIf { it.isNotBlank() && it.trim().trimEnd('。', '.') != NO_POINTS }
		val d = SlackMessages.capItems(decisions, MAX_DECISIONS)
		val t = SlackMessages.capItems(todos, MAX_TODOS)
		val p = SlackMessages.capItems(points, MAX_POINTS)
		if (abs == null && d.isEmpty() && t.isEmpty() && p.isEmpty()) {
			return SlackMessages.truncate("${header(title, durationMs)}\n\n$NO_POINTS")
		}
		return SlackMessages.truncate(
			buildString {
				append(header(title, durationMs))
				if (abs != null) {
					append("\n\n摘要：\n")
					append(abs)
				}
				fun section(label: String, items: List<String>) {
					if (items.isEmpty()) return
					append("\n\n")
					append(label)
					append("：\n")
					items.forEach { append("• ").append(it).append('\n') }
				}
				section("決議", d)
				section("待辦", t)
				section("重點", p)
			}.trimEnd(),
		)
	}

	fun parse(text: String): SlackEndSections {
		if (text.isBlank() || text.trim().trimEnd('。', '.') == NO_POINTS) return SlackEndSections()
		val abstractLines = mutableListOf<String>()
		val decisions = mutableListOf<String>()
		val todos = mutableListOf<String>()
		val points = mutableListOf<String>()
		var section: String? = null
		for (raw in text.lines()) {
			val line = raw.trim()
			if (line.isEmpty()) continue
			val heading = HEADING.matchEntire(line)
			val headingName = heading?.groupValues?.get(1)
			val headingRest = heading?.groupValues?.get(2)?.trim()?.ifEmpty { null }
			if (headingName != null) {
				section = when (headingName) {
					"決議" -> "決議"
					"待辦", "待辦事項", "行動", "行動項目" -> "待辦"
					"摘要" -> "摘要"
					else -> "重點"
				}
				if (headingRest == null) continue
				// same-line content after "摘要：" / "決議：" etc.
				when (section) {
					"摘要" -> abstractLines += headingRest
					"決議" -> decisions += stripLeadingLabel(headingRest, "決議", "決定")
					"待辦" -> todos += stripLeadingLabel(headingRest, "待辦", "TODO", "Action")
					else -> points += headingRest
				}
				continue
			}
			val isBullet = line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")
			val item = line.removePrefix("- ").removePrefix("• ").removePrefix("* ").trim()
			if (item.isEmpty() || item.trim().trimEnd('。', '.') == NO_POINTS) continue
			when (section) {
				null -> if (isBullet) classifyOrphan(item, decisions, todos, points) else abstractLines += line
				"摘要" -> if (isBullet) classifyOrphan(item, decisions, todos, points) else abstractLines += line
				"決議" -> decisions += stripLeadingLabel(item, "決議", "決定")
				"待辦" -> todos += stripLeadingLabel(item, "待辦", "TODO", "Action")
				else -> points += item
			}
		}
		return SlackEndSections(
			abstract = joinAbstract(abstractLines),
			decisions = decisions,
			todos = todos,
			points = points,
		)
	}

	/** Prefer a successful post-recording cloud summary; otherwise a condensed or capped live list. */
	fun compose(
		title: String?,
		durationMs: Long?,
		summaryText: String?,
		fallbackItems: List<String>,
		condensedItems: List<String>? = null,
	): String {
		val parsed = summaryText?.let { parse(it) }
		if (parsed != null && parsed.hasContent) {
			return format(title, durationMs, parsed.abstract, parsed.decisions, parsed.todos, parsed.points)
		}
		val items = when {
			!condensedItems.isNullOrEmpty() -> SlackMessages.capItems(condensedItems, MAX_POINTS)
			else -> SlackMessages.capItems(fallbackItems, MAX_POINTS)
		}
		if (items.isEmpty()) return format(title, durationMs, null, emptyList(), emptyList())
		val decisions = mutableListOf<String>()
		val todos = mutableListOf<String>()
		val points = mutableListOf<String>()
		items.forEach { classifyOrphan(it, decisions, todos, points) }
		return format(title, durationMs, abstract = null, decisions, todos, points)
	}

	private fun classifyOrphan(
		item: String,
		decisions: MutableList<String>,
		todos: MutableList<String>,
		points: MutableList<String>,
	) {
		when {
			startsWithLabel(item, "待辦", "TODO", "Action") -> todos += stripLeadingLabel(item, "待辦", "TODO", "Action")
			startsWithLabel(item, "決議", "決定") -> decisions += stripLeadingLabel(item, "決議", "決定")
			else -> points += item
		}
	}

	private fun startsWithLabel(item: String, vararg labels: String): Boolean =
		labels.any { item.startsWith("$it：") || item.startsWith("$it:") || item.startsWith("$it ") }

	private fun stripLeadingLabel(item: String, vararg labels: String): String {
		var t = item
		for (label in labels) {
			for (sep in listOf("：", ":", " ")) {
				val prefix = label + sep
				if (t.startsWith(prefix)) {
					t = t.removePrefix(prefix).trim()
					break
				}
			}
		}
		return t.ifBlank { item }
	}

	private fun joinAbstract(lines: List<String>): String? {
		val text = lines.joinToString("").trim().ifBlank { return null }
		if (text.trim().trimEnd('。', '.') == NO_POINTS) return null
		val sentences = SENTENCE.split(text).map { it.trim() }.filter { it.isNotEmpty() }
		val clipped = if (sentences.size <= 3) text else sentences.take(3).joinToString("")
		return clipped.take(400).trim().ifBlank { null }
	}
}

/** When the end worker should wait, send the cloud summary, or fall back. */
object SlackEndPolicy {

	const val TIMEOUT_MS = 8 * 60_000L
	const val POST_RECORDING_ENGINE = "cloud-summary"
	const val LIVE_ENGINE = "live-keypoints"

	enum class Wait { READY, WAIT, FALLBACK }

	fun shouldWaitForPipeline(autoAfterRecording: Boolean, autoSummaryAfterTranscript: Boolean): Boolean =
		autoAfterRecording && autoSummaryAfterTranscript

	fun pickPostRecordingSummary(records: List<SummaryRecord>): SummaryRecord? =
		records
			.filter { it.provenance.engine == POST_RECORDING_ENGINE }
			.filter { it.status == RunStatus.SUCCESS && it.text.isNotBlank() }
			.maxByOrNull { it.createdAtMillis }

	fun isInFlight(records: List<SummaryRecord>): Boolean =
		records.any { it.provenance.engine == POST_RECORDING_ENGINE && it.status in IN_FLIGHT }

	fun decide(
		nowMs: Long,
		enqueuedAtMs: Long,
		waitForPipeline: Boolean,
		summary: SummaryRecord?,
		inFlight: Boolean,
		timeoutMs: Long = TIMEOUT_MS,
	): Wait {
		if (summary != null) return Wait.READY
		if (nowMs - enqueuedAtMs >= timeoutMs) return Wait.FALLBACK
		if (waitForPipeline || inFlight) return Wait.WAIT
		return Wait.FALLBACK
	}

	private val IN_FLIGHT = setOf(RunStatus.QUEUED, RunStatus.RUNNING)
}

/** Prompt used only to optionally condense live key points when the post-recording summary never arrives. */
object SlackEndCondense {

	fun prompt(items: List<String>): String {
		val list = SlackMessages.capItems(items, 20).joinToString("\n") { "- $it" }
		return "請把以下即時重點精簡為最多 ${SlackEndMessage.MAX_POINTS} 點，去除重複，保留最重要的事實、決議與待辦。" +
			SpeakerOwnerPrompt.RULE +
			"只輸出「- 」條列，不要開場白。沒有可保留的重點時只輸出：${SlackEndMessage.NO_POINTS}\n\n即時重點：\n$list"
	}

	fun parseReply(reply: String): List<String> =
		SlackMessages.capItems(SlackMessages.bullets(reply), SlackEndMessage.MAX_POINTS)
}
