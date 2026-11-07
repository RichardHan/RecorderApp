package com.eva.transcribe.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live "分句辨識" pipeline: audio callbacks only segment (cheap, never blocks recording);
 * finished utterances go through a bounded queue to ONE background decoder.
 * If the decoder falls behind and the queue is full, the utterance is not decoded and a visible
 * marker is inserted at that position (the recording itself is unaffected) — never a silent loss.
 */
class LiveUtterancePipeline(
	scope: CoroutineScope,
	private val segmenter: UtteranceSegmenter = UtteranceSegmenter(),
	queueCapacity: Int = 6,
	private val decode: suspend (FloatArray) -> String,
) {
	sealed interface Piece {
		val startSample: Long
		data class Text(override val startSample: Long, val text: String) : Piece
		data class Skipped(override val startSample: Long, val samples: Int, val reason: String) : Piece
	}

	private sealed interface Item {
		data class Work(val u: UtteranceSegmenter.Utterance) : Item
		data class Marker(val piece: Piece) : Item
	}

	private val queue = Channel<Item>(queueCapacity)
	private val markers = Channel<Item>(Channel.UNLIMITED)
	private val pieces = ArrayList<Piece>()
	private val _display = MutableStateFlow("")
	val display: StateFlow<String> = _display.asStateFlow()

	/** Every finalized line in audio order (text + visible skip markers); grows only, never truncated. */
	private val _lines = MutableStateFlow<List<String>>(emptyList())
	val lines: StateFlow<List<String>> = _lines.asStateFlow()

	/** Decoded text lines with their start time (ms from pipeline start); skip markers excluded. */
	private val _timed = MutableStateFlow<List<Pair<Long, String>>>(emptyList())
	val timedLines: StateFlow<List<Pair<Long, String>>> = _timed.asStateFlow()

	/** True while one utterance is being decoded. */
	private val _decoding = MutableStateFlow(false)
	val decoding: StateFlow<Boolean> = _decoding.asStateFlow()

	/** Latest non-fatal problem (lagging / failed segment), cleared on next successful decode. */
	private val _warning = MutableStateFlow<String?>(null)
	val warning: StateFlow<String?> = _warning.asStateFlow()

	var droppedSamples = 0L; private set
	var failedUtterances = 0; private set
	var decodedUtterances = 0; private set

	private val worker: Job = scope.launch {
		for (item in queue) {
			drainMarkers()
			when (item) {
				is Item.Marker -> add(item.piece)
				is Item.Work -> {
					_display.value = "（分句辨識中…）"
					_decoding.value = true
					val text = try { decode(item.u.samples) } catch (e: kotlinx.coroutines.CancellationException) { _decoding.value = false; throw e } catch (e: Exception) {
						_decoding.value = false
						failedUtterances++
						add(Piece.Skipped(item.u.startSample, item.u.samples.size, "辨識失敗：${e.message}"))
						_warning.value = "辨識失敗：${e.message}"
						continue
					}
					_decoding.value = false
					decodedUtterances++
					if (text.isNotBlank()) { add(Piece.Text(item.u.startSample, text)); _display.value = text; _warning.value = null }
				}
			}
		}
		drainMarkers()
	}

	private fun drainMarkers() { while (true) { val m = markers.tryReceive().getOrNull() ?: break; if (m is Item.Marker) add(m.piece) } }

	@Synchronized private fun add(p: Piece) {
		pieces += p
		val sorted = pieces.sortedBy { it.startSample }
		_lines.value = sorted.map { render(it) }
		if (p is Piece.Text) _timed.value = sorted.filterIsInstance<Piece.Text>().map { it.startSample * 1000 / 16_000 to it.text }
	}

	private fun render(p: Piece, sampleRate: Int = 16_000): String = when (p) {
		is Piece.Text -> p.text
		is Piece.Skipped -> "[⚠ ${fmt(p.startSample * 1000 / sampleRate)} 起約 ${p.samples / sampleRate} 秒：${p.reason}]"
	}

	/** Called from the audio thread: cheap, non-suspending. */
	fun accept(buffer: ShortArray, length: Int) { segmenter.accept(buffer, length).forEach(::offer) }

	private fun offer(u: UtteranceSegmenter.Utterance) {
		if (queue.trySend(Item.Work(u)).isFailure) {
			droppedSamples += u.samples.size
			val piece = Piece.Skipped(u.startSample, u.samples.size, "辨識跟不上，此段未辨識（錄音完整保存，可事後重轉）")
			markers.trySend(Item.Marker(piece))
			_display.value = "⚠ 辨識跟不上，已略過一段"
			_warning.value = "辨識跟不上，已略過一段（錄音完整保存）"
		}
	}

	/** Flushes the tail utterance and waits (bounded) until every queued utterance is decoded. */
	suspend fun finish(timeoutMs: Long = 180_000): Boolean {
		segmenter.flush().forEach { u ->
			// at stop, wait for room instead of dropping the tail
			if (withTimeoutOrNull(timeoutMs) { queue.send(Item.Work(u)) } == null) offer(u)
		}
		queue.close()
		val ok = withTimeoutOrNull(timeoutMs) { worker.join() } != null
		if (!ok) worker.cancel()
		return ok
	}

	fun cancel() { queue.close(); worker.cancel() }

	@Synchronized fun pieces(): List<Piece> = pieces.sortedBy { it.startSample }

	fun text(sampleRate: Int = 16_000): String = pieces().joinToString("\n") { render(it, sampleRate) }

	private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) }
}
