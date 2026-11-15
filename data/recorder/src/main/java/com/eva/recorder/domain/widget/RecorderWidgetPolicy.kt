package com.eva.recorder.domain.widget

import com.eva.recorder.domain.models.RecorderState
import com.eva.recorder.domain.tile.RecorderTileDisplay
import com.eva.recorder.domain.tile.RecorderTilePolicy

/**
 * Home-screen "一鍵錄音" widget tap policy. Start goes through the recorder screen (same
 * permission + microphone-foreground-service path as the Quick Settings tile). While a
 * recording is already running or paused, a tap only opens the recorder and never stops it.
 */
object RecorderWidgetPolicy {

	fun shouldStartOnTap(state: RecorderState): Boolean =
		RecorderTilePolicy.displayFor(state) == RecorderTileDisplay.IDLE
}
