package com.eva.cloudtranscribe.engine

/**
 * Detects catastrophic repetition loops (e.g. hundreds of the same filler) in ASR output. Deliberately conservative:
 * normal hesitations ("嗯嗯", "好好好"), short repeated phrases and quotes are NOT flagged. Flagged text is never deleted;
 * callers keep it raw, mark it suspicious and exclude it from summaries/coverage.
 */
object RepetitionGuard {
	const val WARNING = "疑似重複幻覺輸出（原文保留，未採用）"
	private val ignorable = Regex("[\\s\\p{P}\\p{S}，。、！？：；「」『』（）［］…～·]+")
	private val speakerTag = Regex("(?m)^\\s*Speaker\\s*\\d+\\s*[:：]")

	fun isPathological(text: String, minChars: Int = 40): Boolean {
		val body = speakerTag.replace(text, "")
		val compact = ignorable.replace(body, "")
		if (compact.length < minChars) return false
		// (a) one short unit (1–6 chars) repeated back-to-back covering >= 80% of the text, >= 15 times
		for (unit in 1..6) {
			var best = 0
			var i = 0
			while (i + unit <= compact.length) {
				val u = compact.substring(i, i + unit)
				var j = i + unit; var count = 1
				while (j + unit <= compact.length && compact.regionMatches(j, u, 0, unit)) { count++; j += unit }
				if (count * unit > best) best = count * unit
				i = if (count > 1) j else i + 1
				if (count >= 15 && count * unit >= compact.length * 0.8) return true
			}
		}
		// (b) many lines that are nearly all identical
		val lines = body.lines().map { ignorable.replace(it, "") }.filter { it.isNotEmpty() }
		if (lines.size >= 12) {
			val top = lines.groupingBy { it }.eachCount().maxOf { it.value }
			if (top >= lines.size * 0.8) return true
		}
		return false
	}

	/** Bounded preview for UI (never renders megabytes of looped text). */
	fun preview(text: String, max: Int = 40): String = text.replace(Regex("\\s+"), " ").take(max) + if (text.length > max) "…" else ""
}
