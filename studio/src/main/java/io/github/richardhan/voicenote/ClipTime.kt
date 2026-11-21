package io.github.richardhan.voicenote

import java.util.Locale

private val clipTimePattern = Regex("""(?:(\d{1,7}):([0-5]\d)|(\d{1,7}))(?:[.,](\d{1,3}))?""")

internal fun parseClipTime(text: String): Int? {
	val match = clipTimePattern.matchEntire(text.trim()) ?: return null
	val (minutes, clockSeconds, seconds, fraction) = match.destructured
	val wholeSeconds = if (minutes.isEmpty()) seconds.toLong() else minutes.toLong() * 60 + clockSeconds.toInt()
	val milliseconds = wholeSeconds * 1000 + fraction.padEnd(3, '0').toInt()
	return milliseconds.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

internal fun formatClipTime(ms: Int): String {
	val base = String.format(Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60)
	if (ms % 1000 == 0) return base
	return base + "." + (ms % 1000).toString().padStart(3, '0').trimEnd('0')
}
