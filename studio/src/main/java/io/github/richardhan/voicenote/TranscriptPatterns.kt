package io.github.richardhan.voicenote

import java.util.Locale

private val terms = Regex("""[\p{IsHan}]+|[\p{L}][\p{L}0-9_-]*""")
private val common = setOf("this", "that", "these", "those", "the", "and", "with", "from", "have", "will", "was", "for", "are", "我們", "我们", "這個", "这个", "就是", "然後", "然后", "一個", "一个", "所以")
internal fun transcriptKeywords(text: String): List<String> {
	val counts = mutableMapOf<String, Int>()
	// Bound the analysis on long recordings while leaving the transcript itself untouched.
	for (word in terms.findAll(text.take(200_000).lowercase(Locale.ROOT))) {
		val token = word.value
		fun count(value: String) { if (value !in common) counts[value] = (counts[value] ?: 0) + 1 }
		if (Character.UnicodeScript.of(token.codePointAt(0)) == Character.UnicodeScript.HAN) {
			for (size in 2..minOf(4, token.length)) token.windowed(size).forEach(::count)
		} else if (token.length > 2) count(token)
	}
	val selected = mutableListOf<String>()
	val ranked = counts.entries.filter { it.value >= 2 }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length }.thenBy { it.key })
	for ((term, _) in ranked) {
		if (selected.none { term in it }) selected += term
		if (selected.size == 8) break
	}
	return selected
}

private val fillers = Regex("""對對對|对对对|那個|那个|就是說|就是说|呃|嗯|\b(?:you know|i mean|um+|uh+|like)\b""", RegexOption.IGNORE_CASE)
internal fun transcriptFillers(text: String) = fillers.findAll(text).groupingBy { it.value.lowercase(Locale.ROOT) }.eachCount()
	.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
