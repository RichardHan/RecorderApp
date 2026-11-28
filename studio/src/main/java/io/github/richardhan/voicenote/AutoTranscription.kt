package io.github.richardhan.voicenote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.work.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

private fun Context.autoPrefs() = getSharedPreferences("auto_transcription", Context.MODE_PRIVATE)
internal fun SpeechConnection.profileId(): String {
	val fields = mutableListOf<Any>(base, model, chatModel, protocol.name, vocabulary, segmentSeconds, concurrentRequests, skipSilence, key)
	// Existing saved consents remain valid when the new options are left at their defaults.
	if (effort.isNotEmpty() || speakerLabels) { fields += effort; fields += speakerLabels }
	return transcriptDigest(fields.joinToString("\u0000")).toHexString()
}

internal fun Context.queueTranscription(file: File) {
	val prefs = autoPrefs()
	val plan = prefs.getString("consent", null) ?: return
	val work = OneTimeWorkRequestBuilder<AutoTranscriptWorker>()
		.setInputData(workDataOf("file" to file.name, "consent" to plan))
		.setConstraints(Constraints.Builder().setRequiredNetworkType(if (prefs.getBoolean("wifi", false)) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
		.addTag("studio-auto-transcript").build()
	WorkManager.getInstance(this).enqueueUniqueWork("auto/${file.name}", ExistingWorkPolicy.KEEP, work)
}

class AutoTranscriptWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result = process(::openHttps)

	internal suspend fun process(open: HttpsOpen): Result = withContext(Dispatchers.IO) {
		val app = applicationContext as StudioApplication
		val prefs = app.autoPrefs()
		val name = inputData.getString("file") ?: return@withContext Result.failure()
		val file = File(File(app.filesDir, "audio"), name)
		val plan = inputData.getString("consent") ?: return@withContext Result.failure()
		val speech = SpeechConnection(app, open)
		fun allowed() = prefs.getString("consent", null) == plan && prefs.getString("profile", null) == speech.profileId()
		if (file.name != name || !file.isFile || !allowed() || !speech.valid()) return@withContext Result.success()
		val library = app.library
		if (withContext(Dispatchers.Main) { file !in library.files }) return@withContext Result.success()
		if (withContext(Dispatchers.Main) { file in library.recognizing }) return@withContext Result.retry()
		withContext(Dispatchers.Main) { library.recognizing.add(file) }
		try {
			if (prefs.getString("transcribed/$name", null) != plan) {
				speech.transcribeNow(file, false)
				check(prefs.edit().putString("transcribed/$name", plan).commit())
			}
			val text = readTranscript(file)
			val promptText = if (prefs.getBoolean("mask", false)) maskIdentifiers(text) else text
			if (allowed() && prefs.getBoolean("summary", false) && speech.chatModel.isNotBlank()) {
				val target = summaryFile(file, text)
				if (!target.isFile) writeSidecar(target, speech.textCompletion(promptText, "Write a factual summary and action items in the transcript's language."))
			}
			if (allowed() && prefs.getBoolean("title", false) && speech.chatModel.isNotBlank() && withContext(Dispatchers.Main) { library.catalog.value(file, "title").isEmpty() }) {
				val title = speech.textCompletion(promptText, "Return one short descriptive title in the language of this recording.").lineSequence().first().take(120)
				withContext(Dispatchers.Main) { if (library.catalog.value(file, "title").isEmpty()) library.catalog.update(file, "title" to title) }
			}
			withContext(Dispatchers.Main) { library.catalog.update(file, "auto" to "done") }
			if (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
				val manager = app.getSystemService(NotificationManager::class.java)
				manager.createNotificationChannel(NotificationChannel("transcripts", app.getString(R.string.transcript), NotificationManager.IMPORTANCE_DEFAULT))
				val openStudio = PendingIntent.getActivity(app, 0, Intent(app, StudioActivity::class.java).setAction(OPEN_LIBRARY), PendingIntent.FLAG_IMMUTABLE)
				manager.notify(name.hashCode(), Notification.Builder(app, "transcripts").setSmallIcon(R.drawable.voice_mark)
					.setContentTitle(app.getString(R.string.transcript_ready)).setContentIntent(openStudio).setAutoCancel(true).build())
			}
			Result.success()
		} catch (e: CancellationException) { throw e }
		catch (_: Exception) {
			withContext(Dispatchers.Main) { library.catalog.update(file, "auto" to "failed") }
			Result.failure()
		} finally { withContext(NonCancellable + Dispatchers.Main) { library.recognizing.remove(file); library.transcriptChanged() } }
	}
}

@Composable
internal fun AutoPanel(speech: SpeechConnection) {
	val context = LocalContext.current
	val prefs = context.autoPrefs()
	var enabled by remember { mutableStateOf(prefs.contains("consent")) }
	var confirm by remember { mutableStateOf(false) }
	var wifi by remember { mutableStateOf(prefs.getBoolean("wifi", false)) }
	var summary by remember { mutableStateOf(prefs.getBoolean("summary", false)) }
	var mask by remember { mutableStateOf(prefs.getBoolean("mask", false)) }
	var title by remember { mutableStateOf(prefs.getBoolean("title", false)) }
	TextButton(enabled = enabled || (speech.valid() && speech.hasSavedConnection && speech.savedProfile == speech.profileId()), onClick = {
		if (enabled) {
			prefs.edit().remove("consent").apply()
			WorkManager.getInstance(context).cancelAllWorkByTag("studio-auto-transcript")
			enabled = false
		} else confirm = true
	}) { Text(stringResource(if (enabled) R.string.auto_off else R.string.auto_on)) }
	if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = {
		Column {
			Text(stringResource(R.string.auto_disclosure, speech.base, speech.model))
			FilterChip(selected = wifi, onClick = { wifi = !wifi }, label = { Text(stringResource(R.string.wifi_only)) })
			if (speech.chatModel.isNotBlank()) {
				FilterChip(selected = summary, onClick = { summary = !summary }, label = { Text(stringResource(R.string.auto_summary, speech.chatModel)) })
				FilterChip(selected = mask, onClick = { mask = !mask }, label = { Text(stringResource(R.string.mask_contacts)) })
				FilterChip(selected = title, onClick = { title = !title }, label = { Text(stringResource(R.string.auto_title, speech.chatModel)) })
			}
		}
	}, confirmButton = { TextButton(onClick = {
		prefs.edit().putString("consent", UUID.randomUUID().toString()).putString("profile", speech.profileId())
			.putBoolean("mask", mask).putBoolean("wifi", wifi).putBoolean("summary", summary && speech.chatModel.isNotBlank()).putBoolean("title", title && speech.chatModel.isNotBlank()).apply()
		enabled = true; confirm = false
	}) { Text(stringResource(R.string.auto_on)) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
