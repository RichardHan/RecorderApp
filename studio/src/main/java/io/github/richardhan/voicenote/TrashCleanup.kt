package io.github.richardhan.voicenote

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

internal fun removeRecording(context: Context, file: File) {
	val directory = File(context.filesDir, "audio")
	require(file.parentFile == directory && (file.extension in audioTypes || file.extension == "youtube"))
	val sharedStem = directory.listFiles().orEmpty().any { it != file && it.nameWithoutExtension == file.nameWithoutExtension && (it.extension in audioTypes || it.extension == "youtube") }
	val owned = directory.listFiles().orEmpty().filter { side ->
		val prefix = file.name + "."
		(side.name.startsWith(prefix) && side.extension in setOf("usage", "timing", "turns", "history", "chat", "checkpoint")) ||
			(!sharedStem && (side == transcriptFile(file) || (side.name.startsWith(file.nameWithoutExtension + "-") && side.extension == "summary")))
	}
	for (item in owned + File(File(context.noBackupFilesDir, "photos"), file.name)) check(!item.exists() || item.deleteRecursively())
	check(!file.exists() || file.delete())
}

internal fun Context.automaticTrash(enabled: Boolean) {
	getSharedPreferences("trash_settings", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
	val work = WorkManager.getInstance(this)
	if (enabled) work.enqueueUniquePeriodicWork("studio-trash", ExistingPeriodicWorkPolicy.KEEP,
		PeriodicWorkRequestBuilder<TrashWorker>(1, TimeUnit.DAYS).build()) else work.cancelUniqueWork("studio-trash")
}

class TrashWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result = withContext(Dispatchers.Main) {
		val app = applicationContext as StudioApplication
		if (!app.getSharedPreferences("trash_settings", Context.MODE_PRIVATE).getBoolean("enabled", false)) return@withContext Result.success()
		val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
		for (file in app.library.trashedFiles) {
			val at = app.library.catalog.value(file, "trashed").toLongOrNull() ?: continue
			if (at <= cutoff && !app.library.purge(file)) return@withContext Result.retry()
		}
		Result.success()
	}
}
