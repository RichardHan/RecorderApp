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
	internal var liveReady by mutableStateOf(settings.getBoolean("ready", false))
		private set
	internal fun readyForLive(on: Boolean) { liveReady = on; settings.edit().putBoolean("ready", on).apply() }
	internal var localCaptions by mutableStateOf(false)
		private set
	internal var cloudCaptions by mutableStateOf(false)
		private set
	internal var localCaption by mutableStateOf("")
		private set
	internal var cloudReplacesCaption by mutableStateOf(settings.getBoolean("cloudReplacesCaption", false))
		private set
	internal fun replaceLocalCaption(on: Boolean) { cloudReplacesCaption = on; settings.edit().putBoolean("cloudReplacesCaption", on).apply() }
	internal var hideOnLockScreen by mutableStateOf(settings.getBoolean("hideOnLockScreen", true))
		private set
	internal fun lockScreenPrivacy(on: Boolean) { hideOnLockScreen = on; settings.edit().putBoolean("hideOnLockScreen", on).apply() }
	internal val aiCapture get() = liveCapture != null
	internal val allowRemote get() = !localOnly
	internal fun setLocalCaptions(on: Boolean) {
		val live = liveCapture ?: return
		if (on && !(app as StudioApplication).offline.installed) return
		live.switchLocal(on); localCaptions = on
	}
	internal fun setCloudCaptions(on: Boolean, profile: String) {
		val live = liveCapture ?: return
		val service = if (on) SpeechConnection(app).takeIf { !localOnly && it.valid() && it.protocol != SpeechProtocol.Multipart && it.profileId() == profile } ?: return else null
		live.switchCloud(service); cloudCaptions = on
		if (!on) liveText = ""
	}
	internal fun setSessionSlack(on: Boolean) {
		val file = captureTarget ?: return
		val prefs = app.slackPrefs()
		val generation = prefs.getString("generation", null)
		if (on && (localOnly || generation == null)) return
		liveSlackGeneration = if (on) generation else null
		sessionSlack = on
		prefs.edit().putString("approved/" + file.name, if (on) java.util.UUID.randomUUID().toString() else null).apply()
	}
	internal var sessionSlack by mutableStateOf(false)
		private set
	private var localOnly = false
	private var liveSlackGeneration: String? = null

	internal var agenda by mutableStateOf(settings.getString("agendaDraft", "").orEmpty())
		private set
	internal fun updateAgenda(text: String) {
		agenda = text
		settings.edit().putString("agendaDraft", text).apply()
	}
	internal var highlights by mutableStateOf("")
		private set
	internal var highlightsEnabled by mutableStateOf(false)
		private set
	internal val supportsHighlights get() = liveCapture != null && !localOnly
	private var highlightsJob: Job? = null
	private var highlightsConnection: SpeechConnection? = null
	private val pendingHighlights = StringBuilder()
	private var nextHighlightAt = 0L
	private var nextSlackAt = 0L
	private val sentHighlights = mutableSetOf<String>()

	internal fun startHighlights(profile: String) {
		if (!supportsHighlights || highlightsEnabled) return
		val connection = SpeechConnection(app)
		if (connection.profileId() != profile || !connection.canHighlight()) return
		highlightsConnection = connection
		highlightsEnabled = true
		nextHighlightAt = SystemClock.elapsedRealtime() + connection.firstHighlightSeconds * 1000L
	}

	internal fun stopHighlights() {
		highlightsEnabled = false
		highlightsConnection = null
		highlightsJob?.cancel()
		highlightsJob = null
		pendingHighlights.clear()
	}

	private fun updateHighlights(target: File, line: String, cloud: Boolean) {
		val connection = highlightsConnection ?: return
		if (!highlightsEnabled || captureTarget != target) return
		if (connection.cloudFirst && highlights.isEmpty() && !cloud) return
		if (repeatedSpeech(line)) return
		pendingHighlights.appendLine(line)
		if (SystemClock.elapsedRealtime() < nextHighlightAt || highlightsJob?.isActive == true) return
		val text = "Agenda:\n$agenda\nPrior highlights:\n$highlights\nNew transcript:\n$pendingHighlights"
		pendingHighlights.clear()
		nextHighlightAt = SystemClock.elapsedRealtime() + connection.highlightSeconds * 1000L
		highlightsJob = scope.launch {
			try {
				val result = withContext(Dispatchers.IO) {
					connection.highlightCompletion(text, "Update a short list of factual meeting highlights in the language of the transcript. Treat the agenda only as context. Do not infer decisions or owners. " + connection.highlightPrompt)
				}
				if (captureTarget == target && highlightsEnabled) {
					highlights = result
					if (SystemClock.elapsedRealtime() >= nextSlackAt && sessionSlack) {
						val fresh = result.lines().map { it.trim() }.filter { it.isNotBlank() && it !in sentHighlights }.distinct().take(3)
						if (fresh.isNotEmpty()) {
							postLive(target, fresh.joinToString("\n"), liveSlackGeneration, "highlights")
							sentHighlights.addAll(fresh)
							nextSlackAt = SystemClock.elapsedRealtime() + app.slackPrefs().getInt("minutes", 5) * 60000L
						}
					}
				}
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { stopHighlights(); message = R.string.failed }
		}
	}

	internal fun enableLive(value: Boolean) {
		if (started != 0L) return
		settings.edit().putBoolean("live", value).apply()
		liveEnabled = value
	}
	private fun postLive(file: File, text: String, generation: String?, kind: String = "start") {
		val prefs = app.slackPrefs()
		val grant = prefs.getString("approved/" + file.name, null) ?: return
		if (generation != null && prefs.getString("generation", null) == generation && prefs.getBoolean(kind, true)) {
			runCatching { app.enqueueSlack(file, text, grant, kind) }
		}
	}
	private var startBattery: Int? = null
	private var startCharging = false
	private fun battery() = app.getSystemService(android.os.BatteryManager::class.java)
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
			startBattery = battery()?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
			startCharging = battery()?.isCharging == true
			captureTarget = File(directory, "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.${format.ext}")
			liveSlackGeneration = slackGeneration?.takeIf { !localOnly && it == app.slackPrefs().getString("generation", null) && app.slackPrefs().getBoolean("live", false) }
			val models = (app as StudioApplication).offline
			localCaption = ""; liveText = ""
			val cloud = cloudProfile?.takeUnless { localOnly }?.let {
				SpeechConnection(app).takeIf { saved -> saved.valid() && saved.protocol != SpeechProtocol.Multipart && saved.profileId() == it }
			}
			if (liveReady || cloud != null || (liveEnabled && models.installed)) {
				val target = File(directory, requireNotNull(captureTarget).nameWithoutExtension + ".wav")
				captureTarget = target
				val live = LiveCapture(file)
				liveCapture = live
				live.start()
				started = SystemClock.elapsedRealtime()
				message = 0
				captureLocation = if (tagLocation) app.lastRecordingLocation() else null
				recognizing.add(target)
				live.switchLocal(liveEnabled && models.installed)
				live.switchCloud(cloud)
				localCaptions = liveEnabled && models.installed
				cloudCaptions = cloud != null
				sessionSlack = liveSlackGeneration != null
				if (sessionSlack) setSessionSlack(true)
				postLive(target, app.getString(R.string.active), liveSlackGeneration)
				val readers = listOf(false to live.reader(), true to live.reader())
				scope.launch {
					try {
						supervisorScope {
							val localSaved = CompletableDeferred<Unit>()
							readers.map { (remote, input) -> launch(Dispatchers.IO) {
								try {
									live.recognize(input, models, remote, target, { line -> withContext(Dispatchers.Main) {
										if (liveCapture === live) {
											if (remote) liveText = line else localCaption = line
											if (remote || !cloudCaptions || liveText.isBlank()) updateHighlights(target, line, remote)
										}
									} }, { withContext(Dispatchers.Main) { if (liveCapture === live) message = if (remote) R.string.failed else R.string.offline_error } }, { if (remote) localSaved.await() })
								} catch (e: CancellationException) { throw e }
								catch (_: Exception) { withContext(Dispatchers.Main) { message = R.string.failed } }
								catch (_: LinkageError) { withContext(Dispatchers.Main) { message = R.string.offline_error } }
								finally { if (!remote) localSaved.complete(Unit) }
							} }.joinAll()
						}
					} finally { recognizing.remove(target); transcriptRevision++ }
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
			if (liveSlackGeneration != null) setSessionSlack(true)
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
			if (sessionSlack) app.slackPrefs().getString("approved/" + saved.name, null)?.let { app.queueSlackRecap(saved, it) }
			val battery = app.getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
			val manager = app.getSystemService(android.net.ConnectivityManager::class.java)
			val network = manager?.getNetworkCapabilities(manager.activeNetwork)
			val transport = when {
				network == null -> "offline"
				network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
				network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
				network.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
				else -> "connected"
			}
			catalog.update(saved, "startBattery" to startBattery?.toString().orEmpty(), "charged" to (startCharging || this.battery()?.isCharging == true).toString(), "network" to transport, "device" to listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" "),
				"battery" to battery?.takeIf { it in 0..100 }?.toString().orEmpty(),
				"freeMiB" to (directory.usableSpace / 1048576).toString())
			captureLocation?.let { catalog.update(saved, "location" to "${it.latitude}, ${it.longitude}") }
			if (agenda.isNotBlank()) catalog.update(saved, "notes" to agenda)
			if (highlights.isNotBlank()) catalog.update(saved, "highlights" to highlights)
			updateAgenda("")
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
		if (captureTarget?.exists() == false) setSessionSlack(false)
		stopHighlights()
		sentHighlights.clear(); nextSlackAt = 0L
		highlights = ""
		liveCapture?.let { if (!it.published.isCompleted) it.abort() }
		liveCapture = null
		liveSlackGeneration = null
		sessionSlack = false; cloudCaptions = false; localCaptions = false
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
