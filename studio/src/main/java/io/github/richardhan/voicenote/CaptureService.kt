package io.github.richardhan.voicenote

import android.app.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class StudioApplication : Application() {
	internal var requestWidgetCapture by androidx.compose.runtime.mutableStateOf(false)
	internal val offline by lazy { OfflineSpeechModels(this) }
	internal val library by lazy { RecordingLibrary(this) }
}

internal enum class CaptureAction { START, PAUSE, RESUME, FINISH, DISCARD }

internal fun Context.controlCapture(action: CaptureAction, slackGeneration: String? = null, cloudProfile: String? = null, localOnly: Boolean = false) {
	val command = Intent(this, CaptureService::class.java).setAction(action.name).putExtra("slackGeneration", slackGeneration).putExtra("cloudProfile", cloudProfile).putExtra("localOnly", localOnly)
	try {
		if (action == CaptureAction.START) startForegroundService(command) else startService(command)
	} catch (_: IllegalStateException) {
		(applicationContext as StudioApplication).library.message = R.string.record_error
	} catch (_: SecurityException) {
		(applicationContext as StudioApplication).library.message = R.string.permission
	}
}

class CaptureService : Service() {
	private val library get() = (application as StudioApplication).library
	private val notifications get() = getSystemService(NotificationManager::class.java)
	private val channel = "recording"
	private val notificationId = 10
	private var callPause: CallPause? = null

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		notifications.createNotificationChannel(NotificationChannel(channel, getString(R.string.active), NotificationManager.IMPORTANCE_LOW))
	}

	private fun action(command: CaptureAction, label: Int): Notification.Action {
		val intent = Intent(this, CaptureService::class.java).setAction(command.name)
		val pending = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
		return Notification.Action.Builder(null, getString(label), pending).build()
	}

	private fun notification(): Notification {
		val open = PendingIntent.getActivity(this, 0, Intent(this, StudioActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
		val builder = Notification.Builder(this, channel)
			.setContentTitle(getString(if (library.isPaused) R.string.recording_paused else R.string.active))
			.setSmallIcon(R.drawable.voice_mark).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
			.addAction(if (library.isPaused) action(CaptureAction.RESUME, R.string.resume_recording) else action(CaptureAction.PAUSE, R.string.pause_recording))
			.addAction(action(CaptureAction.FINISH, R.string.stop))
		if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
		return builder.build()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		when (intent?.action) {
			CaptureAction.START.name -> try {
				startForeground(notificationId, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
				library.start(intent.getStringExtra("slackGeneration"), intent.getStringExtra("cloudProfile"), intent.getBooleanExtra("localOnly", false))
				if (library.started != 0L && library.callPause && callPause == null) {
					callPause = CallPause(this) {
						if (library.started != 0L && !library.isPaused) {
							library.setPaused(true)
							notifications.notify(notificationId, notification())
						}
					}.also { it.listen() }
				}
			} catch (_: SecurityException) { library.message = R.string.permission }
			CaptureAction.PAUSE.name -> library.setPaused(true)
			CaptureAction.RESUME.name -> library.setPaused(false)
			CaptureAction.FINISH.name -> library.finish()
			CaptureAction.DISCARD.name -> library.discard()
		}
		refreshWidgets()
		if (library.started == 0L) {
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf(startId)
		} else notifications.notify(notificationId, notification())
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		callPause?.close()
		library.finish()
		super.onDestroy()
	}
}
