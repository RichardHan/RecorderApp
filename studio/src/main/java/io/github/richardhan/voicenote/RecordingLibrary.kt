package io.github.richardhan.voicenote

import android.net.Uri
import kotlinx.coroutines.*
import androidx.lifecycle.viewModelScope
import android.app.Application
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
	var started by mutableLongStateOf(0L)
		private set
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

	fun peakAmplitude(): Int = try { recorder?.maxAmplitude ?: 0 } catch (_: IllegalStateException) { 0 }

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

	private fun release() {
		runCatching { recorder?.release() }
		recorder = null
		pending?.delete()
		pending = null
		started = 0
	}

	override fun onCleared() { finish() }
}
