package com.eva.cloudtranscribe.qa

import com.eva.cloudtranscribe.model.PlaybackLine
import java.util.Locale
import kotlinx.serialization.Serializable

/** One numbered transcript segment (`S<id>`) the model may cite. */
data class QaSegment(val id: Int, val startMs: Long?, val endMs: Long?, val text: String)

/** Which part of the transcript was actually sent. */
data class QaContext(val segments: List<QaSegment>, val totalSegments: Int) {
	val truncated: Boolean get() = segments.size < totalSegments

	/** Shown with every answer so a truncated context is never mistaken for the full recording. */
	val coverageNote: String?
		get() {
			if (!truncated) return null
			val lastEnd = segments.lastOrNull()?.let { it.endMs ?: it.startMs }
			return if (lastEnd != null) "只用了前 ${RecordingQa.clock(lastEnd)}（第 1–${segments.size} 段，共 $totalSegments 段）"
			else "只用了前 ${segments.size} 段（共 $totalSegments 段）"
		}
}

@Serializable
data class QaCitation(val segmentId: Int, val startMs: Long? = null)

/** One saved question + answer for a recording; deletable, never modifies transcripts or summaries. */
@Serializable
data class QaRecord(
	val id: String,
	val recordingId: Long,
	val transcriptId: String,
	val question: String,
	val answer: String,
	/** Citations that exist and whose content matches the cited segment. */
	val citations: List<QaCitation> = emptyList(),
	/** Citations that were missing, unknown or did not match their segment. */
	val unverifiedCitations: Int = 0,
	val notFound: Boolean = false,
	val coverageNote: String? = null,
	val createdAtMillis: Long,
	val model: String? = null,
) {
	/** The answer makes claims but none of them could be tied to a real segment. */
	val sourceUnverified: Boolean get() = !notFound && (citations.isEmpty() || unverifiedCitations > 0)
}

data class QaParsed(val answer: String, val citations: List<QaCitation>, val unverified: Int, val notFound: Boolean)

/**
 * Single-recording Q&A. Only builds the request and validates the answer; it never talks to a network.
 * The model must answer from the numbered transcript only, cite segments as `〔S3〕` / `〔S3,S5〕`, and reply
 * 「逐字稿中找不到」 when the transcript has no answer. A citation counts only when the segment exists and shares at
 * least one word (≥ 2 CJK characters or an English token) with the sentence that cites it.
 */
object RecordingQa {

	const val NOT_FOUND = "逐字稿中找不到"
	const val MAX_CONTEXT_CHARS = 24_000
	const val MAX_QUESTION_CHARS = 300

	fun segments(lines: List<PlaybackLine>): List<QaSegment> =
		lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
			.mapIndexed { i, line -> QaSegment(i + 1, line.startMs, line.endMs, line.text) }

	/** Keeps segments from the start until [maxChars]; the rest is reported through [QaContext.coverageNote]. */
	fun context(segments: List<QaSegment>, maxChars: Int = MAX_CONTEXT_CHARS): QaContext {
		var used = 0
		val kept = segments.takeWhile { s ->
			used += numbered(s).length + 1
			used <= maxChars
		}
		return QaContext(kept, segments.size)
	}

	private fun numbered(s: QaSegment): String {
		val time = if (s.startMs != null && s.endMs != null) " ${clock(s.startMs)}–${clock(s.endMs)}" else ""
		return "[S${s.id}$time] ${s.text}"
	}

	fun prompt(question: String, context: QaContext): String {
		require(context.segments.isNotEmpty()) { "no segments" }
		require(question.isNotBlank()) { "blank question" }
		val note = context.coverageNote?.let { "（注意：以下只是逐字稿的一部分，$it。）\n" }.orEmpty()
		return "你是會議錄音問答助手。只能根據下面的逐字稿回答問題，不要推測、不要使用外部知識、不要編造。" +
			"每一句回答後面用〔S編號〕標註依據的片段，例如〔S3〕或〔S3,S5〕；只能引用下面出現過的編號，不要輸出時間。" +
			"如果逐字稿裡找不到答案，只回答「$NOT_FOUND」。用繁體中文簡短回答。\n\n" +
			"問題：${question.trim().take(MAX_QUESTION_CHARS)}\n\n${note}逐字稿（每行 \"[S編號 時間] 內容\"）：\n" +
			context.segments.joinToString("\n", transform = ::numbered)
	}

	private val CITATION = Regex("[〔\\[【]\\s*(S\\d+(?:\\s*[,，、]\\s*S?\\d+)*)\\s*[〕\\]】]")
	private val ID = Regex("\\d+")

	fun parse(answer: String, context: QaContext): QaParsed {
		val text = answer.trim()
		val notFound = text.startsWith(NOT_FOUND) || text.removePrefix("「").startsWith(NOT_FOUND)
		val byId = context.segments.associateBy { it.id }
		val verified = LinkedHashMap<Int, QaCitation>()
		var unverified = 0
		var clauseStart = 0
		for (match in CITATION.findAll(text)) {
			val clause = text.substring(clauseStart, match.range.first).let(::lastSentence)
			clauseStart = match.range.last + 1
			ID.findAll(match.groupValues[1]).map { it.value.toInt() }.forEach { id ->
				val segment = byId[id]
				if (segment != null && sharesWord(clause, segment.text)) verified.putIfAbsent(id, QaCitation(id, segment.startMs))
				else unverified++
			}
		}
		return QaParsed(text, verified.values.toList(), unverified, notFound)
	}

	/** Text the citation belongs to: from the last sentence end before it (citations attach to the preceding clause). */
	private fun lastSentence(text: String): String {
		val trimmed = text.trimEnd()
		val cut = trimmed.dropLast(1).indexOfLast { it in "。！？!?\n" }
		return if (cut >= 0) trimmed.substring(cut + 1) else trimmed
	}

	private val ASCII_WORD = Regex("[A-Za-z0-9]{2,}")
	private val CJK = Regex("[\\u3400-\\u9FFF\\uF900-\\uFAFF]+")
	private val STOP = setOf("我們", "你們", "他們", "這個", "那個", "就是", "因為", "所以", "如果", "可以", "沒有", "什麼", "時候", "一個", "提到", "表示", "說明")

	fun sharesWord(clause: String, source: String): Boolean {
		val lowerSource = source.lowercase(Locale.ROOT)
		if (ASCII_WORD.findAll(clause).any { it.value.lowercase(Locale.ROOT) in lowerSource }) return true
		return CJK.findAll(clause).any { run ->
			val s = run.value
			(0 until s.length - 1).any { i -> val w = s.substring(i, i + 2); w !in STOP && w in source }
		}
	}

	fun clock(ms: Long): String {
		val total = ms.coerceAtLeast(0) / 1000
		val h = total / 3600
		return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, (total / 60) % 60, total % 60)
		else String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
	}
}
