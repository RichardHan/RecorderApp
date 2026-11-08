package com.eva.transcribe.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One running live recogniser (one loaded model). */
interface LiveAsrRun {
	val lines: StateFlow<List<String>>
	val timedLines: StateFlow<List<Pair<Long, String>>>
	val decoding: StateFlow<Boolean>
	val warning: StateFlow<String?>
	/** Streaming partial (Vosk only). */
	val partial: StateFlow<String> get() = NO_PARTIAL
	/** Flush tail + wait (bounded); after this no more callbacks. */
	suspend fun finish(): LiveAsrRunResult
	fun cancel()
}

private val NO_PARTIAL: StateFlow<String> = MutableStateFlow("")

data class LiveAsrRunResult(val decoded: Int, val failed: Int, val droppedSamples: Long, val complete: Boolean)

enum class LiveAsrStatus { OFF, NO_MODEL, LOADING, LISTENING, DECODING, ERROR }

data class LiveAsrSnapshot(
	val status: LiveAsrStatus = LiveAsrStatus.OFF,
	/** All finalized lines of this recording: earlier (stopped) runs + current run; never cleared by turning off. */
	val lines: List<String> = emptyList(),
	val timedLines: List<Pair<Long, String>> = emptyList(),
	val message: String? = null,
	val modelId: String? = null,
	val partial: String = "",
)

data class LiveAsrAggregate(
	val modelIds: List<String>,
	val text: String,
	val decoded: Int,
	val failed: Int,
	val droppedSamples: Long,
	val complete: Boolean,
	/** True when recognition was off for part of the recording. */
	val hadGaps: Boolean,
)

/**
 * Turns live on-device recognition on/off during one recording. All transitions are serialized (one Mutex), so rapid
 * toggling never creates duplicate recognisers or overlapping native load/release. Turning off flushes the current
 * run and keeps its text; turning on again starts from the current audio (no retroactive recognition).
 * Never touches the recorder itself.
 */
class LiveAsrController(
	private val scope: CoroutineScope,
	/** Loads the model and starts feeding audio; failure → Result.failure (nothing left running). */
	private val startRun: suspend (modelId: String) -> Result<LiveAsrRun>,
	/** Frees the native model after a run finished. */
	private val releaseModel: suspend () -> Unit = {},
) {
	private val mutex = Mutex()
	private val _state = MutableStateFlow(LiveAsrSnapshot())
	val state: StateFlow<LiveAsrSnapshot> = _state.asStateFlow()

	private var current: LiveAsrRun? = null
	private var currentModel: String? = null
	private var mirror: Job? = null
	private val archivedLines = ArrayList<String>()
	private val archivedTimed = ArrayList<Pair<Long, String>>()
	private val models = LinkedHashSet<String>()
	private var decoded = 0; private var failed = 0; private var dropped = 0L
	private var complete = true
	private var hadGaps = false
	private var everStarted = false
	private var closed = false

	/** Desired state from preferences; serialized and idempotent. */
	suspend fun apply(enabled: Boolean, modelId: String?) = mutex.withLock {
		if (closed) return@withLock
		val want = if (enabled) modelId else null
		if (current != null && want == currentModel) return@withLock
		if (current != null) stopLocked()
		when {
			!enabled -> { if (everStarted) hadGaps = true; publish(LiveAsrStatus.OFF, null) }
			modelId == null -> { if (everStarted) hadGaps = true; publish(LiveAsrStatus.NO_MODEL, "尚未選擇離線模型，請到設定下載／選擇") }
			else -> startLocked(modelId)
		}
	}

	private suspend fun startLocked(modelId: String) {
		publish(LiveAsrStatus.LOADING, null, modelId)
		val run = startRun(modelId).getOrElse { e ->
			if (everStarted) hadGaps = true
			publish(LiveAsrStatus.ERROR, "離線模型載入失敗：${e.message ?: e.javaClass.simpleName}", modelId)
			return
		}
		if (everStarted) hadGaps = true // gap between previous run and this one
		everStarted = true
		current = run; currentModel = modelId; models += modelId
		mirror = scope.launch {
			combine(run.lines, run.timedLines, run.decoding, run.warning, run.partial) { l, t, d, w, p -> Quint(l, t, d, w, p) }.collect { q ->
				_state.value = LiveAsrSnapshot(
					status = if (q.d) LiveAsrStatus.DECODING else LiveAsrStatus.LISTENING,
					lines = archivedLines + q.l, timedLines = archivedTimed + q.t, message = q.w, modelId = modelId, partial = q.p,
				)
			}
		}
		publish(LiveAsrStatus.LISTENING, null, modelId)
	}

	private data class Quint(val l: List<String>, val t: List<Pair<Long, String>>, val d: Boolean, val w: String?, val p: String)

	private suspend fun stopLocked() {
		val run = current ?: return
		val r = run.finish()
		mirror?.cancel(); mirror = null
		archivedLines += run.lines.value
		archivedTimed += run.timedLines.value
		decoded += r.decoded; failed += r.failed; dropped += r.droppedSamples
		complete = complete && r.complete
		current = null; currentModel = null
		releaseModel()
	}

	private fun publish(status: LiveAsrStatus, message: String?, modelId: String? = null) {
		val run = current
		_state.value = LiveAsrSnapshot(
			status = status,
			lines = archivedLines + (run?.lines?.value ?: emptyList()),
			timedLines = archivedTimed + (run?.timedLines?.value ?: emptyList()),
			message = message, modelId = modelId,
		)
	}

	/** Recording stopped: flush the current run and return everything (null when recognition never ran). */
	suspend fun finishAll(): LiveAsrAggregate? = mutex.withLock {
		if (closed) return@withLock null
		stopLocked()
		closed = true
		if (!everStarted) return@withLock null
		LiveAsrAggregate(models.toList(), archivedLines.joinToString("\n"), decoded, failed, dropped, complete && dropped == 0L && failed == 0, hadGaps)
	}

	/**
	 * A NEW recording starts in the same (still bound) service instance: drop everything of the previous recording so
	 * no old line can leak into the new one, and reopen the controller. Serialized with [finishAll], so a previous
	 * recording's finish always completes (and returns its text) before the reset. Late callbacks of an old run cannot
	 * publish afterwards because its mirror job is cancelled and [current] is replaced.
	 */
	suspend fun resetForNewRecording() = mutex.withLock {
		val old = current
		mirror?.cancel(); mirror = null
		current = null; currentModel = null
		if (old != null) { old.cancel(); releaseModel() }
		archivedLines.clear(); archivedTimed.clear(); models.clear()
		decoded = 0; failed = 0; dropped = 0L
		complete = true; hadGaps = false; everStarted = false; closed = false
		_state.value = LiveAsrSnapshot()
	}

	/** Recording cancelled / service destroyed: drop without waiting. */
	fun cancelAll() {
		closed = true
		current?.cancel(); current = null
		mirror?.cancel()
	}
}
