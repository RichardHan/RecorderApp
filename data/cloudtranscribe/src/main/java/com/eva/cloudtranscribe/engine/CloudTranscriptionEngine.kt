package com.eva.cloudtranscribe.engine

import com.eva.cloudtranscribe.api.AudioPayload
import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClient
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SegmentResult
import com.eva.cloudtranscribe.model.SegmentStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Normalised 16 kHz mono audio that can be cut and encoded by time range. */
interface AudioRangeSource {
	val durationMs: Long
	/** True only when the range is measurably silent (RMS-based). Size is never used as a proxy. */
	fun isSilent(startMs: Long, endMs: Long): Boolean
	fun encode(startMs: Long, endMs: Long): AudioPayload
}

interface ChunkCheckpoints {
	fun load(index: Int): SegmentResult?
	fun save(result: SegmentResult)
}

data class EngineConfig(
	val chunkSeconds: Int = CloudSettings.DEFAULT_CHUNK_SECONDS,
	val workers: Int = CloudSettings.DEFAULT_WORKERS,
	val maxAttempts: Int = CloudSettings.MAX_ATTEMPTS,
	val minSplitSeconds: Int = CloudSettings.MIN_CHUNK_SECONDS,
	val hallucinationWarning: Boolean = true,
	val retryBaseDelayMs: Long = 2_000,
	val prompt: String = Prompts.TRANSCRIBE,
	val alternatePrompt: String = Prompts.TRANSCRIBE_ALT,
)

data class EngineResult(
	val status: RunStatus,
	val text: String,
	val segments: List<SegmentResult>,
	val warnings: List<String>,
	val requestCount: Int,
)

class CloudTranscriptionEngine(
	private val client: CloudAiClient,
	private val checkpoints: ChunkCheckpoints,
	private val config: EngineConfig,
) {
	private val requests = AtomicInteger(0)

	fun plan(durationMs: Long): List<LongRange> {
		val chunkMs = config.chunkSeconds.coerceAtLeast(CloudSettings.MIN_CHUNK_SECONDS) * 1000L
		if (durationMs <= 0) return emptyList()
		return (0 until durationMs step chunkMs).map { start -> start until minOf(durationMs, start + chunkMs) }
	}

	suspend fun run(
		source: AudioRangeSource,
		onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
	): EngineResult = coroutineScope {
		val ranges = plan(source.durationMs)
		// hard cap: every chunk may use maxAttempts + 1 empty retry + 3 filter sub-requests
		val budget = ranges.size * (config.maxAttempts + 4)
		val done = AtomicInteger(0)
		val semaphore = Semaphore(config.workers.coerceIn(1, CloudSettings.MAX_WORKERS))
		val results = ranges.mapIndexed { index, range ->
			async(Dispatchers.IO) {
				semaphore.withPermit {
					ensureActive()
					val cached = checkpoints.load(index)?.takeIf { it.isFinal }
					val result = cached ?: processChunk(index, range, source, budget).also(checkpoints::save)
					onProgress(done.incrementAndGet(), ranges.size)
					result
				}
			}
		}.awaitAll().sortedBy { it.index }
		assemble(results)
	}

	private suspend fun processChunk(index: Int, range: LongRange, source: AudioRangeSource, budget: Int): SegmentResult {
		val start = range.first
		val end = range.last + 1
		if (source.isSilent(start, end)) return SegmentResult(index, start, end, SegmentStatus.SILENT)
		val audio = withContext(Dispatchers.Default) { source.encode(start, end) }
		var attempts = 0
		var lastError: String? = null
		var usedAltPrompt = false
		while (attempts < config.maxAttempts + (if (usedAltPrompt) 1 else 0)) {
			if (requests.incrementAndGet() > budget) {
				return SegmentResult(index, start, end, SegmentStatus.FAILED, attempts = attempts, error = "已達請求上限，停止以避免額外計費")
			}
			attempts++
			val prompt = if (usedAltPrompt) config.alternatePrompt else config.prompt
			when (val outcome = client.transcribe(prompt, audio)) {
				is CallOutcome.Text -> return SegmentResult(index, start, end, SegmentStatus.OK, outcome.text, attempts)
				CallOutcome.ContentFiltered -> return splitFiltered(index, start, end, source, attempts, budget)
				CallOutcome.Empty -> {
					if (usedAltPrompt) return SegmentResult(index, start, end, SegmentStatus.EMPTY, attempts = attempts, error = "模型回傳空白（非靜音判定）")
					usedAltPrompt = true
					delay(config.retryBaseDelayMs)
				}
				is CallOutcome.Failure -> {
					lastError = outcome.message
					if (!outcome.retryable) break
					delay(config.retryBaseDelayMs * attempts)
				}
			}
		}
		return SegmentResult(index, start, end, SegmentStatus.FAILED, attempts = attempts, error = lastError ?: "失敗")
	}

	/** Bounded split: at most 3 parts, each >= minSplitSeconds, each sent exactly once. */
	private suspend fun splitFiltered(index: Int, start: Long, end: Long, source: AudioRangeSource, attempts: Int, budget: Int): SegmentResult {
		val minMs = config.minSplitSeconds * 1000L
		val parts = ((end - start) / minMs).coerceAtMost(3).toInt()
		if (parts < 2) return SegmentResult(index, start, end, SegmentStatus.CONTENT_FILTERED, attempts = attempts, error = "內容過濾，片段過短無法再切")
		val step = (end - start) / parts
		val sub = (0 until parts).map { i ->
			val s = start + i * step
			val e = if (i == parts - 1) end else s + step
			if (requests.incrementAndGet() > budget) {
				SegmentResult(i, s, e, SegmentStatus.FAILED, error = "已達請求上限")
			} else when (val outcome = client.transcribe(config.prompt, withContext(Dispatchers.Default) { source.encode(s, e) })) {
				is CallOutcome.Text -> SegmentResult(i, s, e, SegmentStatus.OK, outcome.text, 1)
				CallOutcome.ContentFiltered -> SegmentResult(i, s, e, SegmentStatus.CONTENT_FILTERED, attempts = 1)
				CallOutcome.Empty -> SegmentResult(i, s, e, SegmentStatus.EMPTY, attempts = 1)
				is CallOutcome.Failure -> SegmentResult(i, s, e, SegmentStatus.FAILED, attempts = 1, error = outcome.message)
			}
		}
		val allOk = sub.all { it.status == SegmentStatus.OK }
		return SegmentResult(
			index, start, end,
			status = if (allOk) SegmentStatus.OK else SegmentStatus.CONTENT_FILTERED,
			text = sub.filter { it.status == SegmentStatus.OK }.joinToString("\n") { it.text },
			attempts = attempts + parts,
			parts = sub,
		)
	}

	private fun assemble(results: List<SegmentResult>): EngineResult {
		val warnings = mutableListOf<String>()
		val lengths = results.filter { it.status == SegmentStatus.OK && it.text.isNotBlank() }.map { it.text.length }.sorted()
		val median = if (lengths.size >= 3) lengths[lengths.size / 2] else null
		val total = results.size
		val text = results.joinToString("\n") { seg ->
			val range = "${fmt(seg.startMs)}–${fmt(seg.endMs)}"
			val body = when (seg.status) {
				SegmentStatus.OK -> if (seg.parts.isEmpty()) seg.text else partsText(seg)
				SegmentStatus.SILENT -> "[靜音 $range]"
				SegmentStatus.CONTENT_FILTERED -> if (seg.parts.isEmpty()) "[⚠ 內容過濾，未轉錄 $range]" else partsText(seg)
				SegmentStatus.EMPTY -> "[⚠ 模型空白回應，未轉錄 $range，可重試]"
				SegmentStatus.FAILED -> "[⚠ 轉錄失敗 $range：${seg.error ?: ""}，可重試]"
			}
			if (config.hallucinationWarning && median != null && seg.status == SegmentStatus.OK && seg.text.length > median * 2.5) {
				warnings += "片段 ${seg.index + 1}/$total 長度異常，可能幻覺"
				"[⚠ 可能幻覺 — 片段 ${seg.index + 1}/$total，長度遠超中位數]\n$body"
			} else body
		}
		results.filter { !it.isFinal }.forEach { warnings += "片段 ${it.index + 1} (${fmt(it.startMs)}–${fmt(it.endMs)}) 狀態 ${it.status}" }
		val status = when {
			results.isEmpty() -> RunStatus.FAILED
			results.all { it.isFinal } -> RunStatus.SUCCESS
			results.none { it.status == SegmentStatus.OK } -> RunStatus.FAILED
			else -> RunStatus.PARTIAL
		}
		return EngineResult(status, text, results, warnings, requests.get())
	}

	private fun partsText(seg: SegmentResult) = seg.parts.joinToString("\n") { p ->
		if (p.status == SegmentStatus.OK) p.text else "[⚠ ${if (p.status == SegmentStatus.CONTENT_FILTERED) "內容過濾" else "未完成"}，未轉錄 ${fmt(p.startMs)}–${fmt(p.endMs)}]"
	}

	companion object {
		fun fmt(ms: Long): String {
			val s = ms / 1000
			return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
		}
	}
}

object Prompts {
	const val TRANSCRIBE = "Transcribe this audio verbatim in its original language(s) (Traditional Chinese for Mandarin, keep English words as spoken). " +
		"Format each utterance as `Speaker Name: text` (use Speaker 1, Speaker 2 if names are unknown). " +
		"Do not add commentary, summaries, or timestamps. Do not insert line breaks at pauses."
	const val TRANSCRIBE_ALT = "Please provide a verbatim transcript of the speech in this audio clip. Output only the transcript text, " +
		"formatted as `Speaker: text`. If there is no speech, output exactly [NO SPEECH]."

	fun summary(transcript: String) = "請用繁體中文為以下會議逐字稿撰寫摘要，包含：重點摘要、決議事項、待辦事項（負責人／期限若有）。" +
		"不要捏造逐字稿中沒有的內容。\n\n逐字稿：\n$transcript"
}
