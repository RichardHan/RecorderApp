package com.eva.recorder.data.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.net.toUri
import com.eva.recorder.R
import com.eva.recorder.data.RecorderActionHandlerImpl
import com.eva.recorder.domain.RecorderStatusTracker
import com.eva.recorder.domain.models.RecorderAction
import com.eva.recorder.domain.models.RecorderState
import com.eva.recorder.domain.tile.RecorderTileClick
import com.eva.recorder.domain.tile.RecorderTileDisplay
import com.eva.recorder.domain.tile.RecorderTilePolicy
import com.eva.recorder.domain.tile.TileClickDebouncer
import com.eva.utils.IntentConstants
import com.eva.utils.NavDeepLinks
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val TAG = "RECORDER_QS_TILE"

/**
 * Quick Settings "錄音" tile. It runs only while the system binds it (no always-on mic) and shares
 * the existing recorder service: start goes through the recorder screen (a visible activity is
 * required to start a microphone foreground service and to check the permission), pause / resume are
 * the same service actions the notification sends. There is intentionally no stop on the tile.
 */
@AndroidEntryPoint
internal class RecorderTileService : TileService() {

	@Inject
	lateinit var statusTracker: RecorderStatusTracker

	private val actionHandler by lazy { RecorderActionHandlerImpl(applicationContext) }
	private val debouncer = TileClickDebouncer()
	private var listeningScope: CoroutineScope? = null

	override fun onTileAdded() {
		super.onTileAdded()
		render(statusTracker.state.value)
	}

	override fun onStartListening() {
		super.onStartListening()
		listeningScope?.cancel()
		listeningScope = MainScope().also { scope ->
			statusTracker.state.onEach(::render).launchIn(scope)
		}
	}

	override fun onStopListening() {
		listeningScope?.cancel()
		listeningScope = null
		super.onStopListening()
	}

	override fun onDestroy() {
		listeningScope?.cancel()
		listeningScope = null
		super.onDestroy()
	}

	override fun onClick() {
		super.onClick()
		if (!debouncer.tryAcquire()) return
		when (RecorderTilePolicy.actionFor(statusTracker.state.value)) {
			RecorderTileClick.OPEN_APP_TO_START -> {
				if (isLocked) unlockAndRun { openRecorderToStart() } else openRecorderToStart()
			}

			RecorderTileClick.PAUSE -> actionHandler.onRecorderAction(RecorderAction.PauseRecorderAction)
			RecorderTileClick.RESUME -> actionHandler.onRecorderAction(RecorderAction.ResumeRecorderAction)
			RecorderTileClick.IGNORE -> Unit
		}
	}

	private fun openRecorderToStart() {
		val intent = Intent(Intent.ACTION_VIEW, NavDeepLinks.RECORDER_DESTINATION_PATTERN.toUri()).apply {
			setClassName(applicationContext, IntentConstants.MAIN_ACTIVITY)
			flags = Intent.FLAG_ACTIVITY_NEW_TASK
			putExtra(IntentConstants.EXTRAS_TILE_START_REQUEST_ID, System.currentTimeMillis())
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				val pending = PendingIntent.getActivity(
					applicationContext,
					REQUEST_CODE_START,
					intent,
					PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
				)
				startActivityAndCollapse(pending)
			} else {
				@Suppress("DEPRECATION")
				startActivityAndCollapse(intent)
			}
		} catch (e: Exception) {
			Log.e(TAG, "CANNOT OPEN RECORDER FROM TILE", e)
		}
	}

	private fun render(state: RecorderState) {
		val tile = qsTile ?: return
		val display = RecorderTilePolicy.displayFor(state)
		tile.state = when (display) {
			RecorderTileDisplay.IDLE -> Tile.STATE_INACTIVE
			else -> Tile.STATE_ACTIVE
		}
		tile.label = getString(
			when (display) {
				RecorderTileDisplay.IDLE -> R.string.recorder_tile_label
				RecorderTileDisplay.RECORDING -> R.string.recorder_tile_label_recording
				RecorderTileDisplay.PAUSED -> R.string.recorder_tile_label_paused
				RecorderTileDisplay.PREPARING -> R.string.recorder_tile_label_preparing
			}
		)
		tile.subtitle = getString(
			when (display) {
				RecorderTileDisplay.IDLE -> R.string.recorder_tile_subtitle_start
				RecorderTileDisplay.RECORDING -> R.string.recorder_tile_subtitle_pause
				RecorderTileDisplay.PAUSED -> R.string.recorder_tile_subtitle_resume
				RecorderTileDisplay.PREPARING -> R.string.recorder_tile_subtitle_preparing
			}
		)
		tile.contentDescription = getString(
			when (display) {
				RecorderTileDisplay.IDLE -> R.string.recorder_tile_cd_start
				RecorderTileDisplay.RECORDING -> R.string.recorder_tile_cd_pause
				RecorderTileDisplay.PAUSED -> R.string.recorder_tile_cd_resume
				RecorderTileDisplay.PREPARING -> R.string.recorder_tile_label_preparing
			}
		)
		tile.icon = Icon.createWithResource(
			this,
			if (display == RecorderTileDisplay.PAUSED) R.drawable.ic_pause else R.drawable.ic_microphone,
		)
		tile.updateTile()
	}

	companion object {
		private const val REQUEST_CODE_START = 7301

		/** Ask the system to rebind the tile so it redraws after the recorder state changed elsewhere. */
		fun requestRefresh(context: Context) {
			try {
				requestListeningState(context, ComponentName(context, RecorderTileService::class.java))
			} catch (e: Exception) {
				// tile not added / not allowed right now: it re-renders on the next onStartListening
				Log.d(TAG, "TILE REFRESH SKIPPED", e)
			}
		}
	}
}
