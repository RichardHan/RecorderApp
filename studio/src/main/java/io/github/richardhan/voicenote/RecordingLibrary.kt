package io.github.richardhan.voicenote

import android.net.Uri
import kotlinx.coroutines.*
import androidx.lifecycle.viewModelScope
import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import java.io.File

internal data class FailedImport(val position: Int, val name: String?)
internal data class BatchImportResult(val total: Int, val failures: List<FailedImport>)

class RecordingLibrary(app: Application) : AndroidViewModel(app) {
	private val directory = File(app.filesDir, "audio").apply { mkdirs() }
	private var recorder: MediaRecorder? = null
	private var pending: File? = null
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
	var files by mutableStateOf(savedFiles())
		private set
	var message by mutableIntStateOf(0)
	private fun savedFiles() = directory.listFiles().orEmpty()
		.filter { it.extension in audioTypes || it.extension == "youtube" }.sortedByDescending { it.name }

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
		viewModelScope.launch {
			try { work() }
			catch (e: CancellationException) { throw e }
			catch (_: Exception) { message = R.string.import_error }
			finally {
				files = savedFiles()
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
			val app = getApplication<Application>()
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

	fun start() {
		if (recorder != null) return
		stopPlayback()
		try {
			val file = File.createTempFile("capture-", ".part", directory)
			pending = file
			@Suppress("DEPRECATION")
			val session = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(getApplication()) else MediaRecorder()
			recorder = session
			session.setAudioSource(MediaRecorder.AudioSource.MIC)
			session.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
			session.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
			session.setOutputFile(file.absolutePath)
			session.prepare()
			session.start()
			started = SystemClock.elapsedRealtime()
			message = 0
		} catch (_: Exception) {
			release()
			message = R.string.record_error
		}
	}

	internal fun peakAmplitude(): Int = try {
		recorder?.maxAmplitude ?: 0
	} catch (_: IllegalStateException) {
		0
	}

	fun recordedDurationMs(): Long {
		if (started == 0L) return 0L
		val end = if (isPaused) pausedAt else SystemClock.elapsedRealtime()
		return end - started - totalPausedMs
	}

	fun setPaused(pause: Boolean) {
		val session = recorder ?: return
		if (pause == isPaused) return
		try {
			if (pause) {
				session.pause()
				pausedAt = SystemClock.elapsedRealtime()
			} else {
				session.resume()
				totalPausedMs += SystemClock.elapsedRealtime() - pausedAt
				pausedAt = 0L
			}
			message = 0
		} catch (_: RuntimeException) {
			message = if (pause) R.string.pause_error else R.string.resume_error
		}
	}

	fun finish() {
		val session = recorder ?: return
		try {
			session.stop()
			val file = requireNotNull(pending)
			check(file.length() > 0 && file.renameTo(File(directory, "${System.currentTimeMillis()}.m4a")))
			files = savedFiles()
		} catch (_: Exception) {
			message = R.string.record_error
		} finally {
			release()
		}
	}

	internal fun togglePlayback(file: File) {
		if (recorder != null) return
		val wasPlaying = playingFile == file
		stopPlayback()
		if (wasPlaying) return
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
			session.setOnPreparedListener { if (player === it) it.start() }
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
	}

	private fun release() {
		runCatching { recorder?.release() }
		recorder = null
		pending?.delete()
		pending = null
		started = 0L
		pausedAt = 0L
		totalPausedMs = 0L
	}

	override fun onCleared() {
		stopPlayback()
		finish()
	}
}
