package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
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
	TextButton(onClick = { open = true }) { Text("Slack") }
	if (!open) return
	AlertDialog(onDismissRequest = { open = false }, title = { Text("Slack") }, text = {
		Column {
			OutlinedTextField(hook, { hook = it }, singleLine = true, label = { Text("Incoming webhook URL") }, visualTransformation = PasswordVisualTransformation())
			if (enabled) {
				FilterChip(selected = updates, label = { Text(stringResource(R.string.slack_live)) }, onClick = {
					updates = !updates
					prefs.edit().putBoolean("live", updates).apply()
				})
				TextButton(onClick = {
					prefs.edit().remove("generation").remove("live").apply()
					WorkManager.getInstance(context).cancelAllWorkByTag("slack-outbox")
					ConnectionVault(context, "studio-slack").remove()
					File(context.noBackupFilesDir, "slack-outbox").deleteRecursively()
					enabled = false; updates = false; hook = ""
				}) { Text(stringResource(R.string.disconnect_slack)) }
			}
			if (failed) Text(stringResource(R.string.connection_error))
		}
	}, confirmButton = { TextButton(enabled = slackHook(hook) != null, onClick = {
		try {
			val generation = UUID.randomUUID().toString()
			ConnectionVault(context, "studio-slack").save(JSONObject().put("hook", hook.trim()).put("generation", generation))
			prefs.edit().putString("generation", generation).remove("live").apply()
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
