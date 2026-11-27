package io.github.richardhan.voicenote

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class RecorderTile : TileService() {
	override fun onStartListening() {
		super.onStartListening()
		val active = (application as StudioApplication).library.started != 0L
		qsTile?.apply {
			state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
			label = getString(if (active) R.string.active else R.string.studio)
			updateTile()
		}
	}
	override fun onClick() {
		super.onClick()
		val intent = Intent(this, WidgetRecordEntry::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
		else openOnOlderAndroid(intent)
	}

	// Android 10–13 only expose the Intent overload. Android 14+ goes through PendingIntent above.
	@android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
	@Suppress("DEPRECATION")
	private fun openOnOlderAndroid(intent: Intent) = startActivityAndCollapse(intent)
}
