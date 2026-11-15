package com.eva.recordings.domain.models

data class RecordingPhoto(val id: String, val startMs: Long, val path: String, val caption: String)

object PhotoRules {
	const val MAX_PHOTOS = 30
	const val MAX_CAPTION = 80
	const val MAX_EDGE = 1600
	fun nearestLine(atMs: Long, starts: List<Long?>): Int? = starts.indices.filter { starts[it] != null }
		.minByOrNull { kotlin.math.abs(starts[it]!! - atMs) }
	fun dimensions(width: Int, height: Int): Pair<Int, Int> {
		require(width > 0 && height > 0)
		val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
		return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
	}
}
