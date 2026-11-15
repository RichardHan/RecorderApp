package com.eva.cloudtranscribe.model

/**
 * Frequently mentioned terms of ONE transcript, computed on the phone (spec: market-keywords). Nothing is stored or
 * sent anywhere; the result only feeds the in-transcript search box.
 */
object TranscriptKeywords {

	const val MAX_KEYWORDS = 8
	const val MAX_INPUT_CHARS = 200_000
	private const val MIN_COUNT = 2

	private val HAN_RUN = Regex("""\p{IsHan}+""")
	private val TOKEN = Regex("""[A-Za-z0-9][A-Za-z0-9+#.-]*[A-Za-z0-9+#]""")

	private val STOP = listOf(
		"我們", "你們", "他們", "這個", "那個", "就是", "然後", "一個", "沒有", "可以", "因為", "所以", "如果", "什麼",
		"還是", "已經", "現在", "應該", "這樣", "那樣", "這邊", "那邊", "一下", "一些", "大家", "東西", "時候", "目前",
		"需要", "知道", "覺得", "其實", "對不對", "是不是", "有沒有", "不是", "的話", "這些", "那些", "怎麼", "為什麼",
		"一樣", "比較", "可能", "或者", "但是", "而且", "還有", "我覺得", "你看", "好像", "的話",
		// single particles only; content-bearing single characters (會、要、說…) stay so 會議／要求／說明 survive
		"的", "了", "嗎", "呢", "吧", "啊", "喔", "嗯", "欸", "呀", "啦",
	).sortedByDescending { it.length }
	private val STOP_SPLIT = Regex(STOP.joinToString("|"))
	private val STOP_EN = setOf(
		"the", "and", "for", "that", "this", "with", "are", "was", "you", "we", "it", "is", "to", "of", "in", "on",
		"so", "ok", "okay", "yes", "no", "but", "or", "be", "have", "has", "do", "can", "will", "just", "like", "um", "uh",
		"they", "them", "there", "what", "about", "an", "at", "as", "if", "my", "me", "our", "not", "all", "one",
	)

	private class Candidate(val display: String, val han: Boolean, var count: Int, val firstAt: Int) {
		val length get() = display.length
		val score: Double get() = count * when {
			han && length >= 4 -> 1.7
			han && length == 3 -> 1.4
			han -> 1.0
			length >= 3 -> 1.5
			else -> 1.0
		}
	}

	fun extract(texts: List<String>, max: Int = MAX_KEYWORDS): List<String> {
		val text = buildString {
			for (t in texts) {
				if (length >= MAX_INPUT_CHARS) break
				append(t.take(MAX_INPUT_CHARS - length)).append('\n')
			}
		}
		val candidates = LinkedHashMap<String, Candidate>()
		var position = 0
		fun add(key: String, display: String, han: Boolean) {
			val c = candidates.getOrPut(key) { Candidate(display, han, 0, position) }
			c.count++
			position++
		}
		HAN_RUN.findAll(text).forEach { run ->
			run.value.split(STOP_SPLIT).forEach { piece ->
				for (n in 2..4) {
					if (piece.length < n) break
					for (i in 0..piece.length - n) {
						val gram = piece.substring(i, i + n)
						if (gram.all { it == gram[0] }) continue
						add(gram, gram, han = true)
					}
				}
			}
		}
		TOKEN.findAll(text).forEach { m ->
			val word = m.value
			val key = word.lowercase()
			if (key.length < 2 || key in STOP_EN || key.all { it.isDigit() || it == '.' || it == '-' }) return@forEach
			add(key, word, han = false)
		}

		val ranked = candidates.values.filter { it.count >= MIN_COUNT }
			.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.firstAt })
		val picked = mutableListOf<Candidate>()
		for (c in ranked) {
			if (picked.size >= max) break
			val key = c.display.lowercase()
			// Drop a shorter term that is (almost) always said as part of an already picked longer one, and a longer
			// fragment that only wraps a picked term without being said more on its own.
			val covered = picked.any { p ->
				val pk = p.display.lowercase()
				(pk.contains(key) && c.count <= p.count + p.count / 4) || (key.contains(pk) && c.count <= p.count)
			}
			if (!covered) picked += c
		}
		return picked.map { it.display }
	}
}
