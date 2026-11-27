package io.github.richardhan.voicenote

import android.accounts.Account
import android.content.Context
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
internal fun driveRequest(email: String? = null) = AuthorizationRequest.builder().apply {
	setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
	if (email != null) setAccount(Account(email, "com.google"))
}.build()

private class DriveFailure(val status: Int) : IOException()
internal typealias HttpsOpen = (URL) -> HttpsURLConnection
internal fun openHttps(url: URL) = url.openConnection() as HttpsURLConnection

private fun driveCall(url: String, token: String, method: String = "GET", open: HttpsOpen = ::openHttps, write: ((HttpsURLConnection) -> Unit)? = null): Pair<String, String?> {
	val address = URL(url)
	require(address.protocol == "https" && (address.host == "www.googleapis.com" || address.host.endsWith(".googleapis.com")))
	val connection = open(address)
	try {
		connection.instanceFollowRedirects = false
		connection.requestMethod = method
		connection.connectTimeout = 15000
		connection.readTimeout = 90000
		connection.setRequestProperty("Authorization", "Bearer $token")
		write?.invoke(connection)
		if (connection.responseCode !in 200..299) throw DriveFailure(connection.responseCode)
		return connection.inputStream.bufferedReader().use { it.readText() } to connection.getHeaderField("Location")
	} finally { connection.disconnect() }
}

internal fun driveEmail(token: String): String = JSONObject(driveCall("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)", token).first)
	.getJSONObject("user").getString("emailAddress").also { require(it.isNotBlank()) }

internal fun Context.drivePrefs() = getSharedPreferences("drive_backup", Context.MODE_PRIVATE)

internal fun Context.queueDrive(file: File, automatic: Boolean) {
	val prefs = drivePrefs()
	val account = prefs.getString("account", null) ?: return
	if (automatic && !prefs.getBoolean("auto", false)) return
	val constraints = Constraints.Builder().setRequiredNetworkType(if (prefs.getBoolean("wifi", false)) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
	val work = OneTimeWorkRequestBuilder<DriveWorker>().setInputData(workDataOf("file" to file.name, "account" to account, "auto" to automatic))
		.setConstraints(constraints).addTag("drive-backup").build()
	WorkManager.getInstance(this).enqueueUniqueWork("drive:$account:${file.name}", ExistingWorkPolicy.KEEP, work)
}

class DriveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result = upload(::openHttps) { account ->
		val auth = Tasks.await(Identity.getAuthorizationClient(applicationContext).authorize(driveRequest(account)), 60, TimeUnit.SECONDS)
		if (auth.hasResolution()) null else auth.accessToken
	}

	internal suspend fun upload(open: HttpsOpen, tokenFor: (String) -> String?): Result = withContext(Dispatchers.IO) {
		val context = applicationContext
		val prefs = context.drivePrefs()
		val account = inputData.getString("account") ?: return@withContext Result.failure()
		val name = inputData.getString("file") ?: return@withContext Result.failure()
		val file = File(File(context.filesDir, "audio"), name)
		if (file.name != name || !file.isFile || file.extension !in audioTypes) return@withContext Result.failure()
		val library = (context as StudioApplication).library
		fun enabled() = prefs.getString("account", null) == account && (!inputData.getBoolean("auto", false) || prefs.getBoolean("auto", false))
		suspend fun status(value: String) = withContext(Dispatchers.Main) { library.catalog.update(file, "drive" to value) }
		if (!enabled() || withContext(Dispatchers.Main) { file !in library.files }) return@withContext Result.success()
		try {
			val token = tokenFor(account)
			if (token.isNullOrEmpty()) { status("auth"); return@withContext Result.failure() }
			val root = "https://www.googleapis.com/drive/v3"
			val key = "id/$account/$name"
			val id = prefs.getString(key, null) ?: JSONObject(driveCall("$root/files/generateIds?count=1", token, open = open).first)
				.getJSONArray("ids").getString(0).also { check(prefs.edit().putString(key, it).commit()) }
			// A previous upload can have succeeded even if its final response was lost.
			val existing = try { JSONObject(driveCall("$root/files/$id?fields=id,size,trashed", token, open = open).first) }
				catch (e: DriveFailure) { if (e.status == 404) null else throw e }
			if (existing != null && !existing.optBoolean("trashed") && existing.optLong("size") == file.length()) {
				status("done"); return@withContext Result.success()
			}
			if (existing != null) { status("failed"); return@withContext Result.failure() }
			val mime = audioTypes.getValue(file.extension)
			val title = withContext(Dispatchers.Main) { library.catalog.value(file, "title") }.ifBlank { file.nameWithoutExtension }
			val data = JSONObject().put("id", id).put("name", "$title.${file.extension}").put("mimeType", mime)
			val (_, upload) = driveCall("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable", token, "POST", open) {
				it.doOutput = true
				it.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
				it.setRequestProperty("X-Upload-Content-Type", mime)
				it.setRequestProperty("X-Upload-Content-Length", file.length().toString())
				it.outputStream.use { stream -> stream.write(data.toString().toByteArray()) }
			}
			if (!enabled()) return@withContext Result.success()
			val job = currentCoroutineContext()
			driveCall(requireNotNull(upload), token, "PUT", open) { connection ->
				connection.doOutput = true
				connection.setRequestProperty("Content-Type", mime)
				connection.setFixedLengthStreamingMode(file.length())
				connection.outputStream.use { out -> file.inputStream().use { input ->
					val buffer = ByteArray(65536)
					while (true) {
						job.ensureActive()
						val n = input.read(buffer)
						if (n < 0) break
						out.write(buffer, 0, n)
					}
				} }
			}
			status("done")
			Result.success()
		} catch (e: CancellationException) { throw e }
		catch (e: Exception) {
			val retry = runAttemptCount < 4 && (e !is DriveFailure || e.status in listOf(408, 409, 429) || e.status >= 500)
			status(if (e is DriveFailure && e.status == 401) "auth" else if (retry) "pending" else "failed")
			if (retry) Result.retry() else Result.failure()
		}
	}
}
