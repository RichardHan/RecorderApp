package com.eva.feature_recordings.search.ask

/** One recording whose transcript TEXT may be sent with a cross-recording question. */
internal data class AskSource(val recordingId: Long, val name: String, val text: String)

/** Pure rules for asking AI across search results (spec: docs/proposals/market-ask-across.md). */
internal object AskAcross {
	const val MAX_SOURCES = 6
	const val MAX_CHARS = 48_000
	const val NOT_FOUND = "這些錄音中沒有提到"
	const val TRUNCATED = "（已截斷）"

	/** Transcript-hit recordings first (in hit order), then other search results; distinct. */
	fun candidates(hits: List<Pair<Long, String>>, results: List<Pair<Long, String>>): List<Pair<Long, String>> =
		(hits + results).distinctBy { it.first }

	/** Keeps at most [MAX_SOURCES] candidates that have a non-blank transcript. [text] may be slow (reads files). */
	inline fun pick(candidates: List<Pair<Long, String>>, text: (Long) -> String?): List<AskSource> {
		val out = ArrayList<AskSource>()
		for ((id, name) in candidates) {
			if (out.size >= MAX_SOURCES) break
			val t = text(id)?.trim().orEmpty()
			if (t.isNotEmpty()) out += AskSource(id, name.ifBlank { "錄音" }, t)
		}
		return out
	}

	/** Text actually sent for each source: even share of [MAX_CHARS], cut ones marked. */
	fun budgeted(sources: List<AskSource>): List<String> {
		if (sources.isEmpty()) return emptyList()
		val share = MAX_CHARS / sources.size
		return sources.map { if (it.text.length > share) it.text.take(share) + TRUNCATED else it.text }
	}

	fun sentChars(sources: List<AskSource>): Int = budgeted(sources).sumOf { it.length }

	fun prompt(question: String, sources: List<AskSource>): String = buildString {
		appendLine("你會收到多段錄音的逐字稿，編號為 [R1]、[R2]…。只能根據這些逐字稿回答使用者的問題，不可使用外部知識或猜測。")
		appendLine("每個論點後面附上來源編號，例如 [R1] 或 [R1][R3]。")
		appendLine("如果逐字稿中找不到答案，只回答「$NOT_FOUND」。使用繁體中文，簡潔條列。")
		appendLine()
		budgeted(sources).forEachIndexed { i, text ->
			appendLine("=== [R${i + 1}] ${sources[i].name} ===")
			appendLine(text)
			appendLine()
		}
		append("問題：").append(question.trim())
	}

	private val bracket = Regex("""\[(R\d+(?:\s*[,，、]\s*R?\d+)*)]""")
	private val number = Regex("""\d+""")

	/** 0-based indices of sources the answer actually cites, in first-cited order; out-of-range ignored. */
	fun cited(answer: String, sourceCount: Int): List<Int> = bracket.findAll(answer)
		.flatMap { m -> number.findAll(m.groupValues[1]).map { it.value.toIntOrNull() } }
		.mapNotNull { n -> n?.takeIf { it in 1..sourceCount }?.minus(1) }
		.distinct().toList()
}
