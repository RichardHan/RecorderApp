package com.eva.feature_player.clips

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Small independent player that plays only [startMs, endMs] of a file, so a clip can be previewed without touching
 * the main player's position or the source file.
 */
internal class ClipPreviewPlayer(private val context: Context, private val scope: CoroutineScope) {

	private var player: MediaPlayer? = null
	private var watcher: Job? = null

	var onStateChanged: (Boolean) -> Unit = {}

	val isPlaying: Boolean
		get() = player?.isPlaying == true

	fun play(uri: Uri, startMs: Long, endMs: Long) {
		stop()
		try {
			val mp = MediaPlayer().apply {
				setDataSource(context, uri)
				prepare()
				seekTo(startMs.coerceAtLeast(0L).toInt())
				start()
			}
			player = mp
			onStateChanged(true)
			watcher = scope.launch {
				while (isActive && player === mp) {
					val position = runCatching { mp.currentPosition.toLong() }.getOrDefault(endMs)
					if (position >= endMs || !mp.isPlaying) break
					delay(100)
				}
				if (player === mp) stop()
			}
		} catch (_: Exception) {
			stop()
		}
	}

	fun stop() {
		watcher?.cancel()
		watcher = null
		player?.let { runCatching { it.stop() }; it.release() }
		val wasActive = player != null
		player = null
		if (wasActive) onStateChanged(false)
	}
}
