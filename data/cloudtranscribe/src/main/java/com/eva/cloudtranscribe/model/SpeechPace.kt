package com.eva.cloudtranscribe.model

/** A timed line used for fastest/slowest; times come only from real segments. */
data class PacedLine(val startMs: Long, val endMs: Long, val perMinute: Double)

data class SpeechPaceReport(
	val units: Int,
	/** Null when the transcript has no real timestamps. */
	val perMinute: Double?,
	val fastest: PacedLine?,
	val slowest: PacedLine?,
	/** Top fillers by count, descending; ties keep list order. */
	val fillers: List<Pair<String, Int>>,
	val fillersPerThousand: Double,
)

/** Local pace and filler statistics for one transcript (spec: market-speech-pace). Never sends anything. */
object SpeechPace {

	const val MIN_LINE_MS = 20_000L
	const val TOP_FILLERS = 5

	/** Longest first so 對對對 is not also counted as 對, and you know before know-like substrings. */
	val FILLERS_ZH = listOf("對對對", "那個", "就是", "然後", "嗯", "呃", "欸")
	val FILLERS_EN = listOf("you know", "um", "uh", "like")

	private val HAN = Regex("""\p{IsHan}""")
	private val EN_WORD = Regex("""[A-Za-z0-9]+(?:['’][A-Za-z]+)?""")

	/** Chinese characters count one each; English/number words count one each. */
	fun units(text: String): Int = HAN.findAll(text).count() + EN_WORD.findAll(text).count()

	fun fillerCounts(text: String): List<Pair<String, Int>> {
		var rest = text
		val out = mutableListOf<Pair<String, Int>>()
		for (f in FILLERS_ZH) {
			val n = Regex(Regex.escape(f)).findAll(rest).count()
			if (n > 0) out += f to n
			rest = rest.replace(f, " ")
		}
		for (f in FILLERS_EN) {
			val re = Regex("""(?<![A-Za-z])${Regex.escape(f).replace(" ", "\\E\\s+\\Q")}(?![A-Za-z])""", RegexOption.IGNORE_CASE)
			val n = re.findAll(rest).count()
			if (n > 0) out += f to n
			rest = re.replace(rest, " ")
		}
		val order = (FILLERS_ZH + FILLERS_EN)
		return out.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { order.indexOf(it.first) })
	}

	fun report(lines: List<PlaybackLine>): SpeechPaceReport {
		val all = lines.joinToString("\n") { it.text }
		val units = units(all)
		val timed = lines.filter { it.startMs != null && it.endMs != null && it.endMs > it.startMs }
		val perMinute = if (timed.isEmpty()) null else {
			val ms = timed.sumOf { it.endMs!! - it.startMs!! }
			units(timed.joinToString("\n") { it.text }) / (ms / 60_000.0)
		}
		val paced = timed.filter { it.endMs!! - it.startMs!! >= MIN_LINE_MS }
			.map { PacedLine(it.startMs!!, it.endMs!!, units(it.text) / ((it.endMs - it.startMs) / 60_000.0)) }
		val counts = fillerCounts(all)
		val total = counts.sumOf { it.second }
		return SpeechPaceReport(
			units = units,
			perMinute = perMinute,
			fastest = paced.maxByOrNull { it.perMinute },
			slowest = paced.minByOrNull { it.perMinute }?.takeIf { paced.size > 1 },
			fillers = counts.take(TOP_FILLERS),
			fillersPerThousand = if (units == 0) 0.0 else total * 1000.0 / units,
		)
	}
}
