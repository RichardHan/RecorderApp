package com.eva.cloudtranscribe.model

/** Sentence-level audio ranges from the decoded 16 kHz PCM, not estimated word alignment. */
object OfflineTranscriptSegments {
	fun fromSamples(index: Int, startSample: Long, sampleCount: Int, text: String, error: String? = null): SegmentResult {
		require(index >= 0 && startSample >= 0 && sampleCount > 0)
		val endSample = Math.addExact(startSample, sampleCount.toLong())
		// Divide before multiplying to keep long recordings safe from intermediate overflow.
		fun millis(samples: Long) = (samples / 16_000) * 1000 + (samples % 16_000) * 1000 / 16_000
		return SegmentResult(
			index = index, startMs = millis(startSample), endMs = millis(endSample),
			status = when { error != null -> SegmentStatus.FAILED; text.isBlank() -> SegmentStatus.EMPTY; else -> SegmentStatus.OK },
			text = text, attempts = 1, error = error,
		)
	}
}
