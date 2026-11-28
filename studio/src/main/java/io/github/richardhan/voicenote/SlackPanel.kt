package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.work.WorkManager
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Composable
internal fun SlackPanel() {
	val context = LocalContext.current
	val prefs = context.slackPrefs()
	var open by remember { mutableStateOf(false) }
	var enabled by remember { mutableStateOf(prefs.contains("generation")) }
	var updates by remember { mutableStateOf(prefs.getBoolean("live", false)) }
	var hook by remember { mutableStateOf("") }
	var failed by remember { mutableStateOf(false) }
	var label by remember { mutableStateOf(prefs.getString("channelLabel", "").orEmpty()) }
	var show by remember(open) { mutableStateOf(false) }
	TextButton(onClick = { open = true }) { Text("Slack") }
	if (!open) return
	DisposableEffect(Unit) { onDispose { hook = "" } }
	AlertDialog(onDismissRequest = { open = false }, title = { Text("Slack") }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			OutlinedTextField(hook, { hook = it }, singleLine = true, label = { Text("Incoming webhook URL") }, visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation())
			TextButton(onClick = {
				if (!show && hook.isBlank() && enabled) try { hook = ConnectionVault(context, "studio-slack").load()?.optString("hook").orEmpty() } catch (_: Exception) { failed = true }
				show = !show
			}) { Text(stringResource(R.string.ai_webhook_visible)) }
			TextButton(onClick = {
				try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://api.slack.com/messaging/webhooks"))) }
				catch (_: android.content.ActivityNotFoundException) { failed = true }
			}) { Text(stringResource(R.string.ai_webhook_help)) }
			OutlinedTextField(label, { label = it }, singleLine = true, label = { Text(stringResource(R.string.ai_channel_label)) })
			if (enabled) {
				TextButton(onClick = { prefs.edit().putString("channelLabel", label.trim()).apply() }) { Text(stringResource(R.string.ai_save_label)) }
				val sent = prefs.getLong("sentAt", 0)
				if (sent > 0) Text(stringResource(R.string.ai_last_sync, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(sent))))
				FilterChip(selected = updates, label = { Text(stringResource(R.string.slack_live)) }, onClick = {
					updates = !updates
					if (!updates) {
						val editor = prefs.edit(); prefs.all.keys.filter { it.startsWith("approved/") }.forEach(editor::remove); editor.apply()
						(context.applicationContext as StudioApplication).library.setSessionSlack(false)
					}
					prefs.edit().putBoolean("live", updates).apply()
				})
				for ((kind, label) in listOf("start" to R.string.ai_slack_start, "highlights" to R.string.meeting_live_highlights, "final" to R.string.ai_slack_recap)) {
					var on by remember { mutableStateOf(prefs.getBoolean(kind, true)) }
					FilterChip(selected = on, onClick = { on = !on; prefs.edit().putBoolean(kind, on).apply() }, label = { Text(stringResource(label)) })
				}
				var minutes by remember { mutableStateOf(prefs.getInt("minutes", 5)) }
				Text(stringResource(R.string.ai_slack_interval, minutes))
				FlowRow { for (n in listOf(1, 5, 10, 30)) TextButton(onClick = { minutes = n; prefs.edit().putInt("minutes", n).apply() }) { Text(n.toString()) } }
				var test by remember { mutableStateOf(false) }
				TextButton(onClick = { test = true }) { Text(stringResource(R.string.ai_test)) }
				if (test) AlertDialog(onDismissRequest = { test = false }, text = { Text(stringResource(R.string.ai_slack_test)) },
					confirmButton = { TextButton(onClick = { test = false; context.enqueueSlack(File(context.filesDir, "studio-connection-test"), "Studio connection test") }) { Text(stringResource(R.string.ai_test)) } },
					dismissButton = { TextButton(onClick = { test = false }) { Text(stringResource(R.string.cancel)) } })

				TextButton(onClick = {
					prefs.edit().remove("generation").remove("live").remove("channelLabel").remove("sentAt").apply()
					WorkManager.getInstance(context).cancelAllWorkByTag("slack-outbox")
					ConnectionVault(context, "studio-slack").remove()
					File(context.noBackupFilesDir, "slack-outbox").deleteRecursively()
					enabled = false; updates = false; hook = ""; label = ""
				}) { Text(stringResource(R.string.disconnect_slack)) }
			}
			if (failed) Text(stringResource(R.string.connection_error))
		}
	}, confirmButton = { TextButton(enabled = slackHook(hook) != null, onClick = {
		try {
			val generation = UUID.randomUUID().toString()
			ConnectionVault(context, "studio-slack").save(JSONObject().put("hook", hook.trim()).put("generation", generation))
			prefs.edit().putString("generation", generation).putString("channelLabel", label.trim()).remove("live").remove("sentAt").apply()
			enabled = true; updates = false; hook = ""; open = false
		} catch (_: Exception) { failed = true }
	}) { Text(stringResource(R.string.save_connection)) } }, dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}

@Composable
internal fun SlackShare(file: File, transcript: String) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val library = (context.applicationContext as StudioApplication).library
	var confirm by remember { mutableStateOf(false) }
	val prefs = context.slackPrefs()
	var enabled by remember { mutableStateOf(prefs.contains("generation")) }
	DisposableEffect(prefs) {
		val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> enabled = prefs.contains("generation") }
		prefs.registerOnSharedPreferenceChangeListener(listener)
		onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
	}
	if (!enabled) return
	TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.slack_send)) }
	when (library.catalog.value(file, "slack")) {
		"sent" -> Text(stringResource(R.string.slack_sent))
		"pending" -> Text(stringResource(R.string.slack_pending))
		"unconfirmed" -> Text(stringResource(R.string.slack_unconfirmed))
	}
	if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = { Text(stringResource(R.string.slack_confirm)) }, confirmButton = {
		TextButton(onClick = {
			confirm = false
			scope.launch {
				try {
					withContext(Dispatchers.IO) { context.enqueueSlack(file, transcript) }
					library.catalog.update(file, "slack" to "pending")
				} catch (e: CancellationException) { throw e }
				catch (_: Exception) { library.catalog.update(file, "slack" to "unconfirmed") }
			}
		}) { Text(stringResource(R.string.slack_send)) }
	}, dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
