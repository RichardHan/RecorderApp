package io.github.richardhan.voicenote

import android.app.Application
import android.os.SystemClock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OfflineSpeechModels(private val app: Application) : AndroidViewModel(app) {
	internal val entries = speechModels(app)
	private val prefs = app.getSharedPreferences("offline_model", 0)
	internal var selected by mutableStateOf(entries.firstOrNull { it.id == prefs.getString("selected", null) } ?: entries.first())
		private set
	var installed by mutableStateOf(selected.installed(app))
		private set
	internal fun select(entry: ModelEntry) {
		if (busy) return
		selected = entry
		prefs.edit().putString("selected", entry.id).apply()
		installed = entry.installed(app)
		failed = false
	}
	var busy by mutableStateOf(false)
		private set
	var failed by mutableStateOf(false)
		private set
	var progress by mutableLongStateOf(0L)
		private set
	private var job: Job? = null

	fun changeInstallation() {
		if (busy) return
		val remove = installed
		busy = true
		failed = false
		job = viewModelScope.launch {
			try {
				withContext(Dispatchers.IO) {
					if (remove) check(selected.directory(app).deleteRecursively())
					else selected.install(app) { n -> withContext(Dispatchers.Main) { progress = n } }
				}
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { failed = true }
			finally { installed = selected.installed(app); busy = false }
		}
	}

	fun pause() { job?.cancel() }

	internal fun recognizer() = selected.decoder(app)

	internal suspend fun recognize(file: File) {
		val started = SystemClock.elapsedRealtime()
		val recognizer = recognizer()
		try {
			val segments = JSONArray()
			val words = mutableListOf<String>()
			var samplesSeen = 0L
			val task = currentCoroutineContext()
			fun transcribe(samples: FloatArray) {
				task.ensureActive()
				val text = recognizer.words(samples)
				val start = samplesSeen / 16000.0
				samplesSeen += samples.size
				if (text.isNotBlank()) {
					words.add(text)
					segments.put(JSONObject().put("start", start).put("end", samplesSeen / 16000.0).put("text", text))
				}
			}
			transcribe(decodeForSpeakers(file, ::transcribe))
			task.ensureActive()
			saveTranscription(file, words.joinToString("\n"), TranscriptionReceipt(selected.label + " · offline", SystemClock.elapsedRealtime() - started, "0"), segments)
		} finally { recognizer.close() }
	}
}

@Composable
internal fun OfflineModelPanel(models: OfflineSpeechModels, transcribing: Boolean) {
	var menu by remember { mutableStateOf(false) }
	androidx.compose.foundation.layout.Box {
		TextButton(enabled = !models.busy && !transcribing, onClick = { menu = true }) { Text(models.selected.label) }
		DropdownMenu(menu, { menu = false }) {
			for (entry in models.entries) DropdownMenuItem(text = { Text(entry.label) }, onClick = { models.select(entry); menu = false })
		}
	}
	val context = androidx.compose.ui.platform.LocalContext.current
	val memory = remember(context) { android.app.ActivityManager.MemoryInfo().also {
		context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(it)
	} }
	Text(stringResource(R.string.ai_device_ram, memory.availMem / 1048576, memory.totalMem / 1048576))
	Text(stringResource(R.string.ai_storage, context.filesDir.usableSpace / 1048576, context.filesDir.totalSpace / 1048576))
	var metered by remember { mutableStateOf(false) }
	fun change() {
		if (!models.installed && context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered) metered = true
		else models.changeInstallation()
	}
	if (metered) AlertDialog(onDismissRequest = { metered = false }, text = { Text(stringResource(R.string.metered_model, models.selected.bytes / 1048576)) },
		confirmButton = { TextButton(onClick = { metered = false; models.changeInstallation() }) { Text(stringResource(R.string.download_anyway)) } },
		dismissButton = { TextButton(onClick = { metered = false }) { Text(stringResource(R.string.cancel)) } })
	if (models.busy) {
		Text(stringResource(R.string.download_percent, models.progress.toInt()))
		TextButton(onClick = models::pause) { Text(stringResource(R.string.pause_model)) }
	} else TextButton(enabled = !transcribing, onClick = ::change) {
		if (models.installed) Text(stringResource(R.string.remove_asr)) else Text(stringResource(R.string.download_named_model, models.selected.label, models.selected.bytes / 1048576))
	}
	if (models.failed) Text(stringResource(R.string.offline_error))
}
