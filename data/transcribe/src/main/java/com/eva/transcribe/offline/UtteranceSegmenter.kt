package com.eva.transcribe.offline

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Energy-based phrase segmenter for non-streaming ("分句") recognition at 16 kHz.
 * Every input sample is either inside an emitted utterance, or part of a silence gap that is
 * explicitly counted (never silently dropped). Utterances include pre-roll and post-roll padding.
 * Threshold adapts to the noise floor but is conservative so quiet speech is kept.
 */
class UtteranceSegmenter(
	private val sampleRate: Int = 16_000,
	private val frameMs: Int = 30,
	private val preRollMs: Int = 300,
	private val hangoverMs: Int = 600,
	private val maxUtteranceMs: Int = 20_000,
	private val minSpeechMs: Int = 150,
	private val minAbsRms: Double = 120.0,
	private val noiseFactor: Double = 2.5,
) {
	data class Utterance(val startSample: Long, val samples: FloatArray)

	private val frame = sampleRate * frameMs / 1000
	private val preRollFrames = preRollMs / frameMs
	private val hangFrames = hangoverMs / frameMs
	private val maxSamples = sampleRate.toLong() * maxUtteranceMs / 1000
	private val minSpeechFrames = max(1, minSpeechMs / frameMs)

	private val pending = ShortArray(frame)
	private var pendingLen = 0
	private var consumed = 0L // samples pushed into frames
	private val preRoll = ArrayDeque<ShortArray>()
	private var current: ArrayList<ShortArray>? = null
	private var currentStart = 0L
	private var currentLen = 0L
	private var speechFrames = 0
	private var silentRun = 0
	private var noiseFloor = 200.0

	/** Samples that were classified as silence and not sent to the recognizer. */
	var silenceSamples = 0L
		private set

	fun accept(input: ShortArray, length: Int = input.size): List<Utterance> {
		val out = ArrayList<Utterance>()
		var i = 0
		while (i < length) {
			val n = minOf(frame - pendingLen, length - i)
			System.arraycopy(input, i, pending, pendingLen, n)
			pendingLen += n; i += n
			if (pendingLen == frame) {
				processFrame(pending.copyOf())?.let(out::add)
				pendingLen = 0
			}
		}
		return out
	}

	/** Flush the tail at stop: any open utterance (and a trailing partial frame) is emitted. */
	fun flush(): List<Utterance> {
		val out = ArrayList<Utterance>()
		if (pendingLen > 0) {
			val tail = pending.copyOf(pendingLen)
			pendingLen = 0
			val cur = current
			if (cur != null) { cur += tail; currentLen += tail.size } else silenceSamples += tail.size
			consumed += tail.size
		}
		finish(force = true)?.let(out::add)
		preRoll.forEach { silenceSamples += it.size }
		preRoll.clear()
		return out
	}

	private fun rms(f: ShortArray): Double { var s = 0.0; for (v in f) s += v.toDouble() * v; return sqrt(s / f.size) }

	private fun processFrame(f: ShortArray): Utterance? {
		val start = consumed
		consumed += f.size
		val e = rms(f)
		val threshold = max(minAbsRms, noiseFloor * noiseFactor)
		val speech = e >= threshold
		if (!speech) noiseFloor = noiseFloor * 0.95 + e * 0.05
		val cur = current
		if (cur == null) {
			if (speech) {
				current = ArrayList<ShortArray>().also { list ->
					currentStart = start - preRoll.sumOf { it.size }
					preRoll.forEach(list::add)
					list += f
				}
				currentLen = currentStart.let { start + f.size - it }
				preRoll.clear()
				speechFrames = 1; silentRun = 0
			} else {
				preRoll.addLast(f)
				if (preRoll.size > preRollFrames) silenceSamples += preRoll.removeFirst().size
			}
			return null
		}
		cur += f; currentLen += f.size
		if (speech) { speechFrames++; silentRun = 0 } else silentRun++
		return when {
			currentLen >= maxSamples -> finish(force = true)
			silentRun >= hangFrames -> finish(force = false)
			else -> null
		}
	}

	private fun finish(force: Boolean): Utterance? {
		val cur = current ?: return null
		current = null
		val enough = speechFrames >= minSpeechFrames
		speechFrames = 0; silentRun = 0
		if (!enough && !force) { silenceSamples += currentLen; return null }
		if (!enough && currentLen < frame * minSpeechFrames) { silenceSamples += currentLen; return null }
		val floats = FloatArray(currentLen.toInt())
		var k = 0
		for (chunk in cur) for (v in chunk) floats[k++] = v / 32768f
		return Utterance(currentStart, floats)
	}
}
