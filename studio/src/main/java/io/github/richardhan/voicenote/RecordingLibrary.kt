package io.github.richardhan.voicenote

import android.net.Uri
import kotlinx.coroutines.*
import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.*
import java.io.File

internal data class FailedImport(val position: Int, val name: String?)
internal data class BatchImportResult(val total: Int, val failures: List<FailedImport>)

class RecordingLibrary(private val app: Application) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val settings = app.getSharedPreferences("capture_settings", 0)
	internal var format by mutableStateOf(CaptureFormat.entries.firstOrNull { it.name == settings.getString("format", "") } ?: CaptureFormat.AAC)
		private set
	internal var quality by mutableIntStateOf(settings.getInt("quality", 1).coerceIn(0, 2))
		private set

	internal var callPause by mutableStateOf(settings.getBoolean("callPause", false))
		private set
	internal var tagLocation by mutableStateOf(settings.getBoolean("location", false))
		private set
	internal var liveEnabled by mutableStateOf(settings.getBoolean("live", false))
		private set
	internal var liveText by mutableStateOf("")
		private set
	internal var transcriptRevision by mutableIntStateOf(0)
		private set
	internal val recognizing = mutableStateListOf<File>()
	private var liveCapture: LiveCapture? = null
	private var localOnly = false
	private var liveSlackGeneration: String? = null

	internal fun enableLive(value: Boolean) {
		if (started != 0L) return
		settings.edit().putBoolean("live", value).apply()
		liveEnabled = value
	}
	private fun postLive(file: File, text: String, generation: String?) {
		if (generation != null && app.slackPrefs().getString("generation", null) == generation && app.slackPrefs().getBoolean("live", false)) {
			runCatching { app.enqueueSlack(file, text) }
		}
	}
	private var captureLocation: android.location.Location? = null

	internal fun contextOption(key: String, enabled: Boolean) {
		when (key) {
			"callPause" -> callPause = enabled
			"location" -> tagLocation = enabled
			else -> return
		}
		settings.edit().putBoolean(key, enabled).apply()
	}

	internal fun configure(format: CaptureFormat, quality: Int) {
		if (started != 0L) return
		this.format = format
		this.quality = quality
		settings.edit().putString("format", format.name).putInt("quality", quality).apply()
	}

	private val directory = File(app.filesDir, "audio").apply { mkdirs() }
	internal val catalog = LibraryCatalog(app)
	private val captureMarkers = mutableStateListOf<RecordingMarker>()
	internal val bookmarkCount get() = captureMarkers.size
	private var recorder: MediaRecorder? = null
	private var pending: File? = null
	internal var captureTarget by mutableStateOf<File?>(null)
		private set
	internal var photoRevision by mutableIntStateOf(0)
		private set
	internal fun transcriptChanged() { transcriptRevision++ }
	internal fun photosChanged() { photoRevision++ }
	private val audioManager = requireNotNull(app.getSystemService(AudioManager::class.java))
	private val playbackAttributes = AudioAttributes.Builder()
		.setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
	private var player: MediaPlayer? = null
	private var focus: AudioFocusRequest? = null
	internal var playingFile by mutableStateOf<File?>(null)
		private set
	var started by mutableLongStateOf(0L)
		private set
	private var pausedAt by mutableLongStateOf(0L)
	private var totalPausedMs = 0L
	val isPaused: Boolean get() = pausedAt != 0L
	private var allFiles by mutableStateOf(savedFiles())
	val files: List<File> get() = allFiles.filter { catalog.value(it, "trashed").isEmpty() }
	internal val trashedFiles: List<File> get() = allFiles.filter { catalog.value(it, "trashed").isNotEmpty() }
	internal fun trash(file: File) {
		if (playingFile == file) stopPlayback()
		catalog.update(file, "trashed" to System.currentTimeMillis().toString())
		app.refreshWidgets()
	}

	internal fun purge(file: File): Boolean {
		if (file !in trashedFiles || file in recognizing) return false
		return try {
			removeRecording(app, file)
			catalog.forget(file)
			refresh()
			true
		} catch (_: Exception) { message = R.string.document_error; false }
	}

	internal fun captureBookmark() {
		if (started != 0L) captureMarkers.add(RecordingMarker(recordedDurationMs(), ""))
	}

	internal var playbackPaused by mutableStateOf(false)
		private set
	internal var playbackDuration by mutableIntStateOf(0)
		private set
	internal var playbackSpeed by mutableFloatStateOf(settings.getFloat("speed", 1f))
		private set
	internal var seekRevision by mutableIntStateOf(0)
		private set

	internal fun setSpeed(speed: Float) {
		if (speed !in 0.5f..2f) return
		playbackSpeed = speed
		settings.edit().putFloat("speed", speed).apply()
		val session = player ?: return
		if (playbackDuration > 0 && !playbackPaused) runCatching { session.playbackParams = session.playbackParams.setSpeed(speed) }
	}
	internal fun pausePlayback(pause: Boolean) {
		val session = player ?: return
		if (playbackDuration <= 0 || pause == playbackPaused) return
		try {
			if (pause) session.pause() else {
				session.playbackParams = session.playbackParams.setSpeed(playbackSpeed)
				session.start()
			}
			playbackPaused = pause
		} catch (_: RuntimeException) { playbackFailed() }
	}
	internal fun seekPlayback(ms: Long) {
		if (playbackDuration <= 0) return
		try {
			player?.seekTo(ms.coerceIn(0L, playbackDuration.toLong()), MediaPlayer.SEEK_CLOSEST)
			seekRevision++
		} catch (_: IllegalStateException) { playbackFailed() }
	}
	internal fun playbackPosition(): Long = try { player?.currentPosition?.toLong() ?: 0L }
	catch (_: IllegalStateException) { 0L }
	var message by mutableIntStateOf(0)
	private fun savedFiles() = directory.listFiles().orEmpty()
		.filter { it.extension in audioTypes || it.extension == "youtube" }.sortedByDescending { it.name }

	internal fun refresh() {
		allFiles = savedFiles()
		app.refreshWidgets()
	}

	var importTotal by mutableIntStateOf(0)
		private set
	var importCompleted by mutableIntStateOf(0)
		private set
	val importing: Boolean get() = importTotal > 0
	internal var lastBatch by mutableStateOf<BatchImportResult?>(null)
		private set

	private fun runImport(count: Int, work: suspend () -> Unit) {
		if (importing || count == 0) return
		importTotal = count
		importCompleted = 0
		lastBatch = null
		message = 0
		scope.launch {
			try { work() }
			catch (e: CancellationException) { throw e }
			catch (_: Exception) { message = R.string.import_error }
			finally {
				refresh()
				importTotal = 0
			}
		}
	}

	fun importUrl(url: String) = runImport(1) {
		withContext(Dispatchers.IO) { importLink(directory, url) }
	}

	fun importDocuments(uris: List<Uri>) {
		val documents = uris.distinct()
		runImport(documents.size) {
			val failures = mutableListOf<FailedImport>()
			for ((index, uri) in documents.withIndex()) {
				val failure = withContext(Dispatchers.IO) {
					try {
						importDocument(app, directory, uri)
						null
					} catch (e: CancellationException) {
						throw e
					} catch (_: Exception) {
						FailedImport(index + 1, app.sharedFileName(uri))
					}
				}
				if (failure != null) failures.add(failure)
				importCompleted = index + 1
			}
			if (documents.size > 1) lastBatch = BatchImportResult(documents.size, failures)
			else if (failures.isNotEmpty()) message = R.string.import_error
		}
	}

	fun start(slackGeneration: String? = null, cloudProfile: String? = null, localOnly: Boolean = false) {
		if (liveCapture != null || recorder != null) return
		stopPlayback()
		try {
			val file = File.createTempFile("capture-", ".part", directory)
			pending = file
			this.localOnly = localOnly
			captureTarget = File(directory, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.${format.ext}")
			liveSlackGeneration = slackGeneration?.takeIf { !localOnly && it == app.slackPrefs().getString("generation", null) && app.slackPrefs().getBoolean("live", false) }
			val models = (app as StudioApplication).offline
			liveText = ""
			val cloud = cloudProfile?.takeUnless { localOnly }?.let {
				SpeechConnection(app).takeIf { saved -> saved.valid() && saved.protocol != SpeechProtocol.Multipart && saved.profileId() == it }
			}
			if (cloud != null || (liveEnabled && models.installed)) {
				val target = File(directory, requireNotNull(captureTarget).nameWithoutExtension + ".wav")
				captureTarget = target
				val live = LiveCapture(file)
				liveCapture = live
				live.start()
				started = SystemClock.elapsedRealtime()
				message = 0
				captureLocation = if (tagLocation) app.lastRecordingLocation() else null
				recognizing.add(target)
				val generation = liveSlackGeneration
				postLive(target, app.getString(R.string.active), generation)
				scope.launch {
					try {
						withContext(Dispatchers.IO) { live.recognize(models, cloud, target) { line ->
							withContext(Dispatchers.Main) {
								if (captureTarget == target || target.isFile) {
									if (captureTarget == target) liveText = line
									postLive(target, line, generation)
								}
							}
						} }
						if (target.isFile) postLive(target, app.getString(R.string.saved_recording), generation)
					} catch (e: CancellationException) { throw e }
					catch (_: Exception) { if (captureTarget == target || target.isFile) message = if (cloud == null) R.string.offline_error else R.string.failed }
					catch (_: LinkageError) { message = R.string.offline_error }
					finally { recognizing.remove(target); transcriptRevision++ }
				}
				return
			}
			@Suppress("DEPRECATION")
			val session = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
			recorder = session
			session.setAudioSource(MediaRecorder.AudioSource.MIC)
			session.setOutputFormat(format.container)
			session.setAudioEncoder(format.encoder)
			session.setAudioChannels(1)
			session.setAudioSamplingRate(format.rate)
			session.setAudioEncodingBitRate(format.bitrates[quality])
			captureLocation = if (tagLocation) app.lastRecordingLocation() else null
			if (format == CaptureFormat.AAC) captureLocation?.let { session.setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
			session.setOutputFile(file.absolutePath)
			session.prepare()
			session.start()
			postLive(requireNotNull(captureTarget), app.getString(R.string.active), liveSlackGeneration)
			started = SystemClock.elapsedRealtime()
			message = 0
		} catch (_: Exception) {
			release()
			message = R.string.record_error
		}
	}

	internal fun peakAmplitude(): Int = try {
		liveCapture?.peak ?: recorder?.maxAmplitude ?: 0
	} catch (_: IllegalStateException) {
		0
	}

	fun recordedDurationMs(): Long {
		if (started == 0L) return 0L
		val end = if (isPaused) pausedAt else SystemClock.elapsedRealtime()
		return end - started - totalPausedMs
	}

	fun setPaused(pause: Boolean) {
		val session = recorder
		if (session == null && liveCapture == null) return
		if (pause == isPaused) return
		try {
			if (pause) {
				session?.pause() ?: liveCapture?.pause(true)
				pausedAt = SystemClock.elapsedRealtime()
			} else {
				session?.resume() ?: liveCapture?.pause(false)
				totalPausedMs += SystemClock.elapsedRealtime() - pausedAt
				pausedAt = 0L
			}
			message = 0
		} catch (_: RuntimeException) {
			message = if (pause) R.string.pause_error else R.string.resume_error
		}
	}

	internal fun discard() {
		if (started == 0L) return
		runCatching { recorder?.stop() }
		release()
	}

	fun finish() {
		val session = recorder
		val live = liveCapture
		if (session == null && live == null) return
		try {
			session?.stop() ?: live?.stop()
			val file = requireNotNull(pending)
			val saved = requireNotNull(captureTarget)
			check(file.length() > 0 && file.renameTo(saved))
			live?.published?.complete(true)
			if (live == null) postLive(saved, app.getString(R.string.saved_recording), liveSlackGeneration)
			val battery = app.getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
			catalog.update(saved, "device" to listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" "),
				"battery" to battery?.takeIf { it in 0..100 }?.toString().orEmpty(),
				"freeMiB" to (directory.usableSpace / 1048576).toString())
			captureLocation?.let { catalog.update(saved, "location" to "${it.latitude}, ${it.longitude}") }
			if (captureMarkers.isNotEmpty()) catalog.saveMarkers(saved, captureMarkers)
			refresh()
			if (!localOnly) {
				app.queueDrive(saved, automatic = true)
				app.queueTranscription(saved)
			}
		} catch (_: Exception) {
			message = R.string.record_error
		} finally {
			release()
		}
	}

	internal fun togglePlayback(file: File, seekMs: Long? = null) {
		if (liveCapture != null || recorder != null) return
		val wasPlaying = playingFile == file
		stopPlayback()
		if (wasPlaying && seekMs == null) return
		try {
			val session = MediaPlayer()
			player = session
			val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
				.setAudioAttributes(playbackAttributes)
				.setWillPauseWhenDucked(true)
				.setOnAudioFocusChangeListener { change ->
					if (change < 0 && player === session) stopPlayback()
				}.build()
			check(audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
			focus = request
			session.setAudioAttributes(playbackAttributes)
			session.setOnPreparedListener {
				if (player === it) try {
					playbackDuration = it.duration.coerceAtLeast(0)
					it.playbackParams = it.playbackParams.setSpeed(playbackSpeed)
					if (seekMs != null) it.seekTo(seekMs.coerceIn(0L, it.duration.toLong().coerceAtLeast(0L)), MediaPlayer.SEEK_CLOSEST)
					it.start()
				} catch (_: RuntimeException) { playbackFailed() }
			}
			session.setOnCompletionListener { if (player === it) stopPlayback() }
			session.setOnErrorListener { failed, _, _ ->
				if (player === failed) playbackFailed()
				true
			}
			session.setDataSource(file.absolutePath)
			session.prepareAsync()
			playingFile = file
			message = 0
		} catch (_: Exception) {
			playbackFailed()
		}
	}

	private fun playbackFailed() {
		stopPlayback()
		message = R.string.playback_error
	}

	internal fun stopPlayback() {
		val previous = player
		player = null
		previous?.release()
		focus?.let { audioManager.abandonAudioFocusRequest(it) }
		focus = null
		playingFile = null
		playbackPaused = false
		playbackDuration = 0
	}

	private fun release() {
		liveCapture?.let { if (!it.published.isCompleted) it.abort() }
		liveCapture = null
		liveSlackGeneration = null
		captureMarkers.clear()
		captureLocation = null
		runCatching { recorder?.release() }
		recorder = null
		pending?.delete()
		pending = null
		captureTarget?.takeUnless(File::exists)?.let { File(File(app.noBackupFilesDir, "photos"), it.name).deleteRecursively() }
		captureTarget = null
		started = 0L
		pausedAt = 0L
		totalPausedMs = 0L
	}

}
