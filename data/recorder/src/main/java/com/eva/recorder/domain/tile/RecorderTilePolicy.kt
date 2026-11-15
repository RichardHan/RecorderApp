package com.eva.recorder.domain.tile

import com.eva.recorder.domain.models.RecorderState

/** What a tap on the Quick Settings recorder tile does for the current recorder state. */
enum class RecorderTileClick {
	/** Nothing is being recorded: open the recorder screen, which starts after the mic-permission check. */
	OPEN_APP_TO_START,
	PAUSE,
	RESUME,

	/** A start is in flight; ignore so a double tap never opens two sessions. */
	IGNORE,
}

/** What the tile shows. Paused is a distinct third state, not the same as idle. */
enum class RecorderTileDisplay {
	IDLE,
	RECORDING,
	PAUSED,
	PREPARING,
}

object RecorderTilePolicy {

	fun displayFor(state: RecorderState): RecorderTileDisplay = when (state) {
		RecorderState.RECORDING -> RecorderTileDisplay.RECORDING
		RecorderState.PAUSED -> RecorderTileDisplay.PAUSED
		RecorderState.PREPARING -> RecorderTileDisplay.PREPARING
		RecorderState.IDLE, RecorderState.COMPLETED, RecorderState.CANCELLED -> RecorderTileDisplay.IDLE
	}

	/**
	 * Tap only toggles start / pause / resume. Stopping (which saves or discards) stays in the
	 * notification or the app so a mis-tap in the shade can never end a recording.
	 */
	fun actionFor(state: RecorderState): RecorderTileClick = when (displayFor(state)) {
		RecorderTileDisplay.IDLE -> RecorderTileClick.OPEN_APP_TO_START
		RecorderTileDisplay.RECORDING -> RecorderTileClick.PAUSE
		RecorderTileDisplay.PAUSED -> RecorderTileClick.RESUME
		RecorderTileDisplay.PREPARING -> RecorderTileClick.IGNORE
	}
}

/** Drops taps that arrive within [windowMillis] of the last accepted tap. */
class TileClickDebouncer(
	private val windowMillis: Long = 800L,
	private val clock: () -> Long = { System.currentTimeMillis() },
) {
	private var lastAccepted: Long? = null

	@Synchronized
	fun tryAcquire(): Boolean {
		val now = clock()
		val last = lastAccepted
		if (last != null && now - last in 0 until windowMillis) return false
		lastAccepted = now
		return true
	}
}

/**
 * Remembers which tile start requests (by id) were already handled, so a rotation, recreation or a
 * replayed deep-link intent never starts a second recording.
 */
class TileStartRequestGate(initiallyHandled: Collection<Long> = emptyList()) {
	private val handled = initiallyHandled.toMutableSet()

	val handledIds: List<Long>
		get() = handled.toList()

	/** Returns true once for a new [requestId]; false for repeats or a missing id. */
	@Synchronized
	fun consume(requestId: Long?): Boolean {
		if (requestId == null || requestId <= 0L) return false
		return handled.add(requestId)
	}
}
