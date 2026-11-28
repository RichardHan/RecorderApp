package io.github.richardhan.voicenote

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.*
import java.io.File

internal fun Context.playRecording(file: File, seekMs: Long? = null) {
	val intent = Intent(this, PlaybackService::class.java).setAction("play").putExtra("file", file.name)
	seekMs?.let { intent.putExtra("seek", it) }
	try { startForegroundService(intent) } catch (_: IllegalStateException) {
		(applicationContext as StudioApplication).library.message = R.string.playback_error
	}
}

class PlaybackService : Service() {
	private val library get() = (application as StudioApplication).library
	private lateinit var session: MediaSession
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val channel = "playback"
	private val id = 12
	private val manager get() = getSystemService(NotificationManager::class.java)
	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		manager.createNotificationChannel(NotificationChannel(channel, getString(R.string.play), NotificationManager.IMPORTANCE_LOW))
		session = MediaSession(this, "Studio playback")
		session.setCallback(object : MediaSession.Callback() {
			override fun onPlay() = library.pausePlayback(false)
			override fun onPause() = library.pausePlayback(true)
			override fun onSeekTo(pos: Long) = library.seekPlayback(pos)
			override fun onStop() = library.stopPlayback()
		})
	}

	private fun notification(file: File?): Notification {
		val open = PendingIntent.getActivity(this, 0, Intent(this, StudioActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
		val command = if (library.playbackPaused) "resume" else "pause"
		val pending = PendingIntent.getService(this, 0, Intent(this, PlaybackService::class.java).setAction(command), PendingIntent.FLAG_IMMUTABLE)
		val stop = PendingIntent.getService(this, 0, Intent(this, PlaybackService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
		return Notification.Builder(this, channel).setSmallIcon(R.drawable.voice_mark)
			.setContentTitle(file?.let(library.catalog::title) ?: getString(R.string.play)).setContentIntent(open)
			.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
			.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (library.playbackPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
				getString(if (library.playbackPaused) R.string.play else R.string.pause_playback), pending).build())
			.addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_delete), getString(R.string.stop_playback), stop).build())
			.setOnlyAlertOnce(true).setOngoing(!library.playbackPaused).build()
	}

	private var watcher: Job? = null
	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			"play" -> {
				val file = library.files.firstOrNull { it.name == intent.getStringExtra("file") }
				startForeground(id, notification(file), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
				if (file != null) library.togglePlayback(file, intent.getLongExtra("seek", -1L).takeIf { it >= 0 })
			}
			"pause" -> library.pausePlayback(true)
			"resume" -> library.pausePlayback(false)
			"stop" -> library.stopPlayback()
		}
		watcher?.cancel()
		watcher = scope.launch {
			snapshotFlow { listOf(library.playingFile, library.playbackPaused, library.playbackSpeed, library.playbackDuration, library.seekRevision) }.collect {
				val file = library.playingFile
				if (file == null) {
					stopForeground(STOP_FOREGROUND_REMOVE)
					stopSelf(startId)
				} else {
					session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, library.catalog.title(file))
						.putLong(MediaMetadata.METADATA_KEY_DURATION, library.playbackDuration.toLong()).build())
					session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
						.setState(if (library.playbackPaused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, library.playbackPosition(), if (library.playbackPaused) 0f else library.playbackSpeed).build())
					session.isActive = true
					manager.notify(id, notification(file))
				}
			}
		}
		return START_NOT_STICKY
	}
	override fun onDestroy() {
		scope.cancel()
		library.stopPlayback()
		session.release()
		super.onDestroy()
	}
}
