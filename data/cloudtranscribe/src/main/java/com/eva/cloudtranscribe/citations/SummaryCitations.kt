package com.eva.cloudtranscribe.citations

import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SegmentStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import kotlinx.serialization.Serializable

/** A verified link from one line of a summary to one real cloud segment. Times come only from the segment. */
@Serializable
data class Citation(
	val lineIndex: Int,
	val segmentIndex: Int,
	val startMs: Long,
	val endMs: Long,
)

/** A citable piece of the source transcript, numbered S1, S2, … in time order. */
data class CiteSegment(val number: Int, val startMs: Long, val endMs: Long, val text: String)

/** Result of parsing a cited summary: marker-free text plus only the citations that passed verification. */
data class CitedSummary(val text: String, val citations: List<Citation>)

/**
 * Summary citations (spec A / PR #3). The model only ever sees segment numbers; times are resolved locally from
 * [SegmentResult]. A citation is kept only when the number exists AND the line shares a real word with that segment.
 * This proves overlap of words, not that the meaning comes from that segment.
 */
object SummaryCitations {

	private val MARKER = Regex("""\s*〔\s*(S\d+(?:\s*[,，、]\s*S\d+)*)\s*〕""")
	private val NUMBER = Regex("""S(\d+)""")
	private val HAN_RUN = Regex("""\p{IsHan}+""")
	private val TOKEN = Regex("""[A-Za-z0-9][A-Za-z0-9._+-]*[A-Za-z0-9]""")
	private const val MAX_PER_LINE = 3

	/** Filler bigrams that would make almost any line "match". */
	private val STOP = setOf(
		"我們", "你們", "他們", "這個", "那個", "就是", "然後", "一個", "沒有", "可以", "因為", "所以", "如果", "什麼",
		"還是", "已經", "現在", "應該", "這樣", "那樣", "這邊", "那邊", "一下", "一些", "大家", "東西", "時候", "目前", "需要",
	)
	private val STOP_SPLIT = Regex(STOP.joinToString("|"))
	private val STOP_EN = setOf("the", "and", "for", "that", "this", "with", "are", "was", "you", "we", "it", "is", "to", "of", "in", "on")

	/**
	 * Citable segments of a transcript, or null when it has no reliable segment timing (offline / live / legacy).
	 * Split segments use their sub-parts' times; FAILED, filtered, empty and silent pieces are never citable.
	 */
	fun segmentsOf(record: TranscriptRecord): List<CiteSegment>? {
		if (record.source != TranscriptSource.CLOUD) return null
		if (record.status != RunStatus.SUCCESS && record.status != RunStatus.PARTIAL) return null
		val pieces = record.segments.sortedBy { it.index }.flatMap { s -> if (s.parts.isNotEmpty()) s.parts else listOf(s) }
			.filter { it.status == SegmentStatus.OK && it.text.isNotBlank() && it.endMs > it.startMs && it.startMs >= 0 }
		if (pieces.isEmpty()) return null
		return pieces.mapIndexed { i, p -> CiteSegment(i + 1, p.startMs, p.endMs, p.text.trim()) }
	}

	fun numberedTranscript(segments: List<CiteSegment>): String =
		segments.joinToString("\n") { "[S${it.number} ${clock(it.startMs)}–${clock(it.endMs)}] ${it.text}" }

	const val INSTRUCTION =
		"逐字稿每段開頭有 [S編號 時間] 標記。每條重點句尾附上它依據的片段編號，例如〔S3〕或〔S3,S5〕，最多三個；" +
			"找不到依據就不要附，不要編造編號，也不要在其他地方使用〔〕符號。"

	/**
	 * Citation prompt: the normal [basePrompt] (built from the numbered transcript) with [INSTRUCTION] inserted
	 * right before the transcript, so format rules and "目前無重點" stay as they are.
	 */
	fun withInstruction(basePrompt: String): String {
		val marker = "\n\n逐字稿：\n"
		val at = basePrompt.indexOf(marker)
		return if (at < 0) "$basePrompt\n$INSTRUCTION"
		else basePrompt.substring(0, at) + "\n" + INSTRUCTION + basePrompt.substring(at)
	}

	/** Strips every 〔S…〕 marker and keeps only verified citations. Never throws. */
	fun parse(reply: String, segments: List<CiteSegment>): CitedSummary {
		val byNumber = segments.associateBy { it.number }
		val citations = mutableListOf<Citation>()
		val lines = reply.trim().lines().mapIndexed { lineIndex, raw ->
			val numbers = MARKER.findAll(raw).flatMap { m -> NUMBER.findAll(m.groupValues[1]).map { it.groupValues[1].toIntOrNull() } }
				.filterNotNull().distinct().toList()
			val clean = MARKER.replace(raw, "").trimEnd()
			numbers.mapNotNull { byNumber[it] }.filter { sharesWord(clean, it.text) }.take(MAX_PER_LINE).forEach { seg ->
				citations += Citation(lineIndex, seg.number, seg.startMs, seg.endMs)
			}
			clean
		}
		return CitedSummary(lines.joinToString("\n"), citations)
	}

	/** True when [line] and [source] share a ≥2-char Han bigram or an English/number token (filler excluded). */
	fun sharesWord(line: String, source: String): Boolean {
		val a = words(line)
		if (a.isEmpty()) return false
		return words(source).any { it in a }
	}

	private fun words(text: String): Set<String> {
		val out = HashSet<String>()
		// Cut filler words out first so bigrams spanning two fillers (e.g. 是這 in 就是這個) never count.
		HAN_RUN.findAll(text).forEach { m ->
			m.value.split(STOP_SPLIT).forEach { piece -> piece.windowed(2).forEach(out::add) }
		}
		TOKEN.findAll(text).map { it.value.lowercase() }.filter { it.length >= 2 && it !in STOP_EN }.forEach(out::add)
		return out
	}

	fun clock(ms: Long): String {
		val total = (ms / 1000).coerceAtLeast(0)
		val h = total / 3600
		val m = (total % 3600) / 60
		val s = total % 60
		return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
	}
}
