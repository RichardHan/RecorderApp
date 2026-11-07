package com.eva.cloudtranscribe.live

/** Decides when a key-point summary may run. Pure; all times are recorded-audio milliseconds. */
object SummaryGate {
	/**
	 * @param elapsedMs recorded audio so far
	 * @param lastSummaryAtMs elapsed time of the last summary attempt (null = never)
	 * @param successfulCloudChunks number of successful cloud chunks so far
	 * @param contentVersion monotonically increasing counter of new usable content
	 * @param summarizedVersion content version included in the last successful summary
	 * @param inFlight a summary request is still running
	 */
	fun shouldRun(
		settings: LiveCloudSettings,
		elapsedMs: Long,
		lastSummaryAtMs: Long?,
		successfulCloudChunks: Int,
		contentVersion: Long,
		summarizedVersion: Long,
		inFlight: Boolean,
	): Boolean {
		if (!settings.isActive || inFlight) return false
		if (contentVersion <= summarizedVersion) return false
		if (elapsedMs < settings.firstSummaryDelayMs) return false
		if (settings.requireCloudBeforeFirstSummary && successfulCloudChunks == 0) return false
		return lastSummaryAtMs == null || elapsedMs - lastSummaryAtMs >= settings.summaryIntervalMs
	}
}

/** Merges cloud spans with provisional offline lines without duplicating audio already covered by cloud. */
object TranscriptMerge {
	/** End of the contiguous-or-not region covered by any cloud span attempt (failed spans count: offline stays visible there). */
	fun coveredEndMs(spans: List<CloudSpan>): Long = spans.filter { it.covers }.maxOfOrNull { it.endMs } ?: 0L

	/** Offline lines that are not inside any successful cloud span; these are shown/sent as provisional. */
	fun provisional(spans: List<CloudSpan>, offline: List<OfflineLine>): List<OfflineLine> {
		val ok = spans.filter { it.covers }
		return offline.filter { line -> ok.none { line.startMs >= it.startMs && line.startMs < it.endMs } }
			.sortedBy { it.startMs }
	}

	fun cloudText(spans: List<CloudSpan>): String =
		spans.sortedBy { it.startMs }.filter { it.covers }.joinToString("\n") { it.text.trim() }

	/** Builds the summary request text: previous key points + new cloud text + provisional offline text. Never rewrites the transcript. */
	fun summaryPrompt(
		userPrompt: String,
		previousKeyPoints: String,
		newCloudText: String,
		provisionalText: String,
		maxChars: Int = 24_000,
	): String = buildString {
		append(LiveCloudSettings.migrateSummaryPrompt(userPrompt))
		append("\n\n【目前重點】\n").append(previousKeyPoints.ifBlank { "（尚無）" })
		append("\n\n【新增逐字稿（雲端）】\n").append(newCloudText.takeLast(maxChars).ifBlank { "（無）" })
		if (provisionalText.isNotBlank()) append("\n\n【暫定：最新離線辨識，尚未有雲端稿】\n").append(provisionalText.takeLast(maxChars / 4))
	}
}

/**
 * Bounded FIFO of audio chunks waiting for cloud transcription. Never grows without bound: when full the oldest
 * waiting chunk is dropped and reported (the recording itself is unaffected and can be fully re-transcribed later).
 */
class BoundedChunkQueue<T>(private val capacity: Int = 3) {
	private val items = ArrayDeque<T>()
	var droppedCount = 0; private set

	/** Returns the dropped element if the queue overflowed. */
	@Synchronized fun offer(item: T): T? {
		var dropped: T? = null
		if (items.size >= capacity) { dropped = items.removeFirst(); droppedCount++ }
		items.addLast(item)
		return dropped
	}

	@Synchronized fun poll(): T? = items.removeFirstOrNull()
	@Synchronized fun size(): Int = items.size
	@Synchronized fun clear() = items.clear()
}

/** Guards against results from an old/stopped session being applied to the current one. */
class SessionGuard {
	@Volatile private var current: String? = null
	@Synchronized fun start(id: String) { current = id }
	@Synchronized fun close() { current = null }
	fun isCurrent(id: String): Boolean = id == current
}

/** One line of the merged live view. */
data class MergedLine(val startMs: Long?, val text: String, val kind: Kind) {
	enum class Kind { CLOUD, OFFLINE_PROVISIONAL, OFFLINE_UNALIGNED, CLOUD_FAILED, CLOUD_SUSPICIOUS, OFFLINE_SUSPICIOUS }
}

/**
 * Merged live transcript: cloud text replaces ONLY the audio ranges it successfully covered; offline lines outside
 * those ranges stay (provisional). Failed/skipped cloud chunks keep their offline lines plus a compact marker.
 * Offline lines without timestamps (Vosk) are never aligned: when no timed offline lines exist they are listed
 * separately as unaligned. Handles out-of-order spans, gaps and partial overlap by start-time containment only.
 */
object LiveMerge {
	fun merge(
		spans: List<CloudSpan>,
		timedOffline: List<OfflineLine>,
		untimedOffline: List<String> = emptyList(),
	): List<MergedLine> {
		val ok = spans.filter { it.covers }
		val out = ArrayList<MergedLine>()
		ok.forEach { out += MergedLine(it.startMs, it.text.trim(), MergedLine.Kind.CLOUD) }
		spans.filter { !it.ok || it.suspicious }.forEach { sp ->
			// a failed chunk whose range was later covered by a successful one is not shown
			if (ok.none { it.startMs <= sp.startMs && it.endMs >= sp.endMs })
				out += if (sp.suspicious) MergedLine(sp.startMs, com.eva.cloudtranscribe.engine.RepetitionGuard.preview(sp.text), MergedLine.Kind.CLOUD_SUSPICIOUS)
				else MergedLine(sp.startMs, sp.error ?: "雲端轉錄失敗", MergedLine.Kind.CLOUD_FAILED)
		}
		TranscriptMerge.provisional(spans, timedOffline).forEach {
			out += if (com.eva.cloudtranscribe.engine.RepetitionGuard.isPathological(it.text))
				MergedLine(it.startMs, com.eva.cloudtranscribe.engine.RepetitionGuard.preview(it.text), MergedLine.Kind.OFFLINE_SUSPICIOUS)
			else MergedLine(it.startMs, it.text, MergedLine.Kind.OFFLINE_PROVISIONAL)
		}
		out.sortWith(compareBy<MergedLine> { it.startMs ?: Long.MAX_VALUE }.thenBy { it.kind.ordinal })
		if (timedOffline.isEmpty()) untimedOffline.filter { it.isNotBlank() }.forEach { out += MergedLine(null, it, MergedLine.Kind.OFFLINE_UNALIGNED) }
		return out
	}

	/** End of the latest successful cloud chunk, for the "雲端已更新" indicator. */
	fun latestCloudEndMs(spans: List<CloudSpan>): Long? = spans.filter { it.covers }.maxOfOrNull { it.endMs }
}
