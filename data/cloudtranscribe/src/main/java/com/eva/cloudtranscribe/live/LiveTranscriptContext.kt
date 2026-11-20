package com.eva.cloudtranscribe.live

import com.eva.cloudtranscribe.engine.Prompts

/** One live-cloud transcribe request, including optional overlap audio and text context. */
data class LiveTranscribeJob(
	val samples: ShortArray,
	val startMs: Long,
	val endMs: Long,
	val overlapMs: Long,
	val prompt: String,
	val previousSentences: String = "",
	val knownTerms: List<String> = emptyList(),
)

/** Per-chunk timing for debugging (wall-clock, recorded-audio bounds). */
data class ChunkLatency(
	val startMs: Long,
	val endMs: Long,
	val overlapMs: Long,
	/** Duration of the uploaded audio, including the leading overlap. */
	val uploadedMs: Long,
	val transcribeMs: Long,
	val refineMs: Long? = null,
)

/** Pure helpers for cutting a leading overlap into a later chunk's upload. */
object LiveAudioOverlap {

	fun samplesFor(overlapMs: Long, sampleRate: Int): Int {
		if (overlapMs <= 0L || sampleRate <= 0) return 0
		return ((overlapMs * sampleRate) / 1000L).toInt().coerceAtLeast(0)
	}

	fun uploadedMs(newAudioMs: Long, overlapMs: Long): Long = newAudioMs.coerceAtLeast(0L) + overlapMs.coerceAtLeast(0L)

	fun tail(samples: ShortArray, overlapMs: Long, sampleRate: Int): ShortArray {
		val n = samplesFor(overlapMs, sampleRate)
		if (n <= 0 || samples.isEmpty()) return ShortArray(0)
		val take = minOf(n, samples.size)
		return samples.copyOfRange(samples.size - take, samples.size)
	}

	fun prepend(overlap: ShortArray, fresh: ShortArray): ShortArray {
		if (overlap.isEmpty()) return fresh
		if (fresh.isEmpty()) return overlap
		return ShortArray(overlap.size + fresh.size).also {
			System.arraycopy(overlap, 0, it, 0, overlap.size)
			System.arraycopy(fresh, 0, it, overlap.size, fresh.size)
		}
	}
}

/**
 * Boundary text de-duplication: drop a leading region of [incoming] that fuzzy-matches the suffix of [previous].
 * Never invents text; if there is no confident match, [incoming] is returned unchanged.
 */
object LiveTranscriptOverlap {

	fun lastSentences(text: String, count: Int = 3): String {
		if (text.isBlank() || count <= 0) return ""
		val parts = SENTENCE.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
		return parts.takeLast(count).joinToString("")
	}

	fun trimLeadingOverlap(previous: String, incoming: String, minChars: Int = 3): String {
		if (previous.isBlank() || incoming.isBlank()) return incoming.trim()
		val prev = normalize(previous)
		val inc = normalize(incoming)
		if (prev.text.isEmpty() || inc.text.isEmpty()) return incoming.trim()
		val max = minOf(prev.text.length, inc.text.length)
		var best = 0
		for (n in max downTo minChars) {
			if (prev.text.regionMatches(prev.text.length - n, inc.text, 0, n)) {
				best = n
				break
			}
		}
		if (best < minChars) return incoming.trim()
		val cutAt = inc.index[best - 1] + 1
		return incoming.substring(cutAt).trimStart(*TRIM_LEAD)
	}

	private data class Normalized(val text: String, val index: IntArray)

	private fun normalize(s: String): Normalized {
		val out = StringBuilder(s.length)
		val idx = IntArray(s.length)
		var n = 0
		s.forEachIndexed { i, c ->
			if (c.isWhitespace() || c in PUNCT) return@forEachIndexed
			out.append(c.lowercaseChar())
			idx[n++] = i
		}
		return Normalized(out.toString(), idx.copyOf(n))
	}

	private val SENTENCE = Regex("(?<=[。！？!?\\.])\\s*")
	private const val PUNCT = "，。,.!?！？、；;:：…—–-「」『』\"'()（）[]【】"
	private val TRIM_LEAD = charArrayOf('，', '。', ',', '.', '、', '；', ';', ' ', '\n', '\t', '！', '？', '!', '?')
}

/**
 * Names and terms taken only from this recording's transcript, custom vocabulary, or meeting notes.
 * Never invents entries.
 */
object LiveKnownTerms {

	const val MAX_TERMS = 40

	fun fromSeeds(vocabulary: List<String>, meetingNotes: String): List<String> =
		merge(emptyList(), vocabulary.map { it.trim() }.filter { it.isNotEmpty() } + extract(meetingNotes))

	fun extract(text: String): List<String> {
		if (text.isBlank()) return emptyList()
		val found = LinkedHashSet<String>()
		INTRO.findAll(text).forEach { m ->
			val name = m.groupValues[1].trim()
			if (name.isNotEmpty() && !isGenericSpeaker(name)) found += name
		}
		EN.findAll(text).forEach { m ->
			val t = m.value
			if (t.lowercase() !in STOP && !isGenericSpeaker(t)) found += t
		}
		return found.toList()
	}

	fun merge(existing: List<String>, incoming: List<String>, max: Int = MAX_TERMS): List<String> {
		val out = LinkedHashSet<String>()
		existing.forEach { if (it.isNotBlank()) out += it }
		incoming.forEach { if (it.isNotBlank()) out += it }
		return out.take(max)
	}

	private fun isGenericSpeaker(t: String): Boolean =
		GENERIC_SPEAKER.matches(t.trim())

	private val EN = Regex("[A-Za-z][A-Za-z0-9._+-]{1,39}")
	private val INTRO = Regex("(?:我是|我叫|我的名字是|叫做)([\\u4e00-\\u9fffA-Za-z]{2,12})")
	private val GENERIC_SPEAKER = Regex("(?i)speaker\\s*\\d+")
	private val STOP = setOf(
		"the", "and", "or", "to", "of", "in", "on", "for", "is", "are", "we", "you", "it",
		"this", "that", "with", "at", "as", "be", "by", "an", "a", "not", "if", "so", "ok",
		"yes", "no", "i", "me", "my", "our", "your", "but", "from", "was", "were", "have",
		"has", "had", "do", "did", "will", "can", "just", "about", "into",
	)
}

/** Builds the live transcribe prompt. Context is marked so the model must not re-output it. */
object LiveTranscribePrompt {

	fun build(
		base: String,
		overlapMs: Long,
		previousSentences: String,
		knownTerms: List<String>,
		enhance: Boolean,
	): String {
		if (!enhance) return base
		val tail = previousSentences.trim()
		val terms = knownTerms.map { it.trim() }.filter { it.isNotEmpty() }.take(LiveKnownTerms.MAX_TERMS)
		if (overlapMs <= 0L && tail.isEmpty() && terms.isEmpty()) return base
		return buildString {
			append(base)
			append("\n\n【前文脈絡（只供銜接與辨識，不要再輸出、不要續寫或改寫）】")
			if (overlapMs > 0L) {
				val sec = if (overlapMs % 1000L == 0L) "${overlapMs / 1000L}" else "%.1f".format(overlapMs / 1000.0)
				append("\n本段音訊開頭約 ").append(sec).append(" 秒是與前一段重疊的上下文。請只轉錄重疊之後的新內容；開頭若與前文重複請略過，不要重複輸出。")
			}
			if (tail.isNotEmpty()) {
				append("\n前一段結尾：\n").append(tail.take(1_200))
			}
			if (terms.isNotEmpty()) {
				append("\n已知名稱／用語（僅供辨識拼寫，不可臆測或添加未出現的詞）：")
					.append(terms.joinToString("、"))
			}
		}
	}
}

/** Text-only boundary refine: fix splits, punctuation, obvious mis-hearings of known terms; never add content. */
object LiveBoundaryRefine {

	fun prompt(previousTail: String, newText: String, knownTerms: List<String>): String = buildString {
		append("請只修正以下「新片段」與前文交界的標點、斷句，以及已知名稱／用語的明顯誤聽。")
		append("必須逐字保留原意，不可改寫、不可意譯、不可刪除或新增事實或句子。")
		append("若無需修改，把新片段原文原樣輸出。只輸出修正後的新片段正文，不要開場白。")
		if (previousTail.isNotBlank()) append("\n\n【前一段結尾】\n").append(previousTail.take(1_200))
		append("\n\n【新片段】\n").append(newText)
		val terms = knownTerms.map { it.trim() }.filter { it.isNotEmpty() }
		if (terms.isNotEmpty()) append("\n\n【已知名稱／用語】\n").append(terms.joinToString("、"))
	}

	/**
	 * Accepts a refine reply only when it looks like a punctuation / spelling fix of [raw],
	 * not added or invented content. Returns null when the raw text should be kept.
	 */
	fun accept(raw: String, reply: String): String? {
		val refined = reply.trim().trim('`', '"')
		if (refined.isEmpty() || Prompts.isNoSpeech(refined)) return null
		if (com.eva.cloudtranscribe.engine.RepetitionGuard.isPathological(refined)) return null
		val r = raw.trim()
		if (r.isEmpty()) return null
		if (normalizedKey(refined) == normalizedKey(r)) {
			return if (refined == r) r else refined
		}
		// allow modest length change (punctuation, typo fixes) but reject added sentences
		if (refined.length > r.length * 4 / 3 + 16) return null
		if (refined.length < r.length * 2 / 3 - 16) return null
		val rawTok = tokens(r)
		val refTok = tokens(refined)
		if (refTok.any { token -> rawTok.none { closeToken(it, token) } }) return null
		return refined
	}

	private fun normalizedKey(s: String): String =
		s.filter { !it.isWhitespace() && it !in "，。,.!?！？、；;:：…「」『』\"'" }.lowercase()

	private fun tokens(s: String): List<String> =
		s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

	private fun closeToken(a: String, b: String): Boolean {
		if (a == b) return true
		val maxEdits = when {
			minOf(a.length, b.length) >= 4 -> 2
			minOf(a.length, b.length) >= 3 -> 1
			else -> 0
		}
		return maxEdits > 0 && levenshtein(a, b) <= maxEdits
	}

	private fun levenshtein(a: String, b: String): Int {
		if (a == b) return 0
		if (a.isEmpty()) return b.length
		if (b.isEmpty()) return a.length
		val prev = IntArray(b.length + 1) { it }
		val cur = IntArray(b.length + 1)
		for (i in 1..a.length) {
			cur[0] = i
			for (j in 1..b.length) {
				val cost = if (a[i - 1] == b[j - 1]) 0 else 1
				cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
			}
			for (j in prev.indices) prev[j] = cur[j]
		}
		return prev[b.length]
	}
}
