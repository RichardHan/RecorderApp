package com.eva.player_shared.util

/**
 * Maps bookmark timestamps onto a 0–1 seek-bar track.
 * Out-of-range or invalid times are dropped; identical positions are kept once.
 */
object BookmarkTickPositions {
	fun ratios(positionsMs: List<Long>, totalMs: Long): List<Float> {
		if (totalMs <= 0L) return emptyList()
		return positionsMs.mapNotNull { ms ->
			if (ms < 0L) return@mapNotNull null
			val ratio = (ms.toDouble() / totalMs.toDouble()).toFloat()
			ratio.takeIf { it in 0f..1f }
		}.distinct()
	}
}
