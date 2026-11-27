package io.github.richardhan.voicenote

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal fun slackHook(value: String): URI? = runCatching { URI(value.trim()) }.getOrNull()?.takeIf {
	it.scheme == "https" && it.host in setOf("hooks.slack.com", "hooks.slack-gov.com") && it.port == -1 &&
		it.userInfo == null && it.query == null && it.fragment == null && Regex("/services/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+").matches(it.path)
}
internal fun Context.slackPrefs() = getSharedPreferences("slack_sync", Context.MODE_PRIVATE)

internal fun Context.enqueueSlack(file: File, message: String) {
	val generation = slackPrefs().getString("generation", null) ?: return
	val dir = File(noBackupFilesDir, "slack-outbox").apply { mkdirs() }
	val payload = File(dir, UUID.randomUUID().toString())
	writeSidecar(payload, message)
	val work = OneTimeWorkRequestBuilder<SlackWorker>().setInputData(workDataOf("id" to payload.name, "file" to file.name, "generation" to generation))
		.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("slack-outbox").build()
	WorkManager.getInstance(this).enqueueUniqueWork("slack/$generation/${file.name}", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
}

class SlackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result = deliver(::openHttps)

	internal suspend fun deliver(open: HttpsOpen): Result = withContext(Dispatchers.IO) {
		val context = applicationContext
		val id = inputData.getString("id") ?: return@withContext Result.failure()
		val generation = inputData.getString("generation")
		val prefs = context.slackPrefs()
		val payload = File(File(context.noBackupFilesDir, "slack-outbox"), UUID.fromString(id).toString())
		if (generation == null || prefs.getString("generation", null) != generation) { payload.delete(); return@withContext Result.success() }
		if (!payload.isFile) return@withContext Result.success()
		val file = File(File(context.filesDir, "audio"), inputData.getString("file") ?: return@withContext Result.failure())
		val library = (context as StudioApplication).library
		suspend fun status(value: String) = withContext(Dispatchers.Main) { library.catalog.update(file, "slack" to value) }
		val cursor = "offset/$id"
		try {
			val secret = ConnectionVault(context, "studio-slack").load() ?: return@withContext Result.failure()
			if (secret.getString("generation") != generation) { payload.delete(); return@withContext Result.success() }
			val url = requireNotNull(slackHook(secret.getString("hook"))).toURL()
			val chunks = payload.readText().chunked(2800)
			for (i in prefs.getInt(cursor, 0) until chunks.size) {
				currentCoroutineContext().ensureActive()
				if (prefs.getString("generation", null) != generation) { payload.delete(); return@withContext Result.success() }
				val conn = open(url)
				try {
					conn.instanceFollowRedirects = false
					conn.connectTimeout = 15000
					conn.readTimeout = 20000
					conn.requestMethod = "POST"
					conn.doOutput = true
					conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
					val json = JSONObject().put("text", chunks[i]).put("mrkdwn", false).put("unfurl_links", false).put("unfurl_media", false)
					conn.outputStream.use { it.write(json.toString().toByteArray()) }
					if (conn.responseCode == 429 && runAttemptCount < 5) return@withContext Result.retry()
					check(conn.responseCode == 200 && conn.inputStream.bufferedReader().use { it.readText() }.trim() == "ok")
					check(prefs.edit().putInt(cursor, i + 1).commit())
				} finally { conn.disconnect() }
			}
			status("sent")
		} catch (e: CancellationException) { throw e }
		catch (_: Exception) {
			// Webhooks have no idempotency key. Surface an ambiguous delivery for manual retry.
			status("unconfirmed")
		}
		prefs.edit().remove(cursor).apply()
		payload.delete()
		Result.success()
	}
}
