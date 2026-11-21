package io.github.richardhan.voicenote

import android.app.Application
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.*
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*

private const val modelUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/8d42ab5f1db14cad987fac57aeb17d070f9e180d/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
private const val modelBytes = 343490560L
private const val sha256 = "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0"

class LocalSummary(app: Application) : AndroidViewModel(app) {
	private val modelFile = File(app.noBackupFilesDir, "qwen3-0.6b-v1.litertlm")
	var installed by mutableStateOf(modelFile.length() == modelBytes)
		private set
	var busy by mutableStateOf(false)
		private set
	var failed by mutableStateOf(false)
		private set
	var revision by mutableIntStateOf(0)
		private set

	private fun runTask(block: suspend () -> Unit) {
		if (busy) return
		busy = true
		failed = false
		viewModelScope.launch {
			try { withContext(Dispatchers.IO) { block() }; installed = modelFile.length() == modelBytes; revision++ }
			catch (e: CancellationException) { throw e }
			catch (_: Exception) { failed = true }
			catch (_: LinkageError) { failed = true }
			finally { busy = false }
		}
	}

	fun download() = runTask {
		val temp = File.createTempFile("summary-", ".part", modelFile.parentFile)
		try {
			val digest = MessageDigest.getInstance("SHA-256")
			val conn = URL(modelUrl).openConnection().apply { connectTimeout = 20000; readTimeout = 60000 }
			conn.getInputStream().use { input ->
				temp.outputStream().buffered().use { out ->
					val buf = ByteArray(65536)
					var total = 0L
					while (true) {
						currentCoroutineContext().ensureActive()
						val n = input.read(buf)
						if (n < 0) break
						total += n
						check(total <= modelBytes)
						out.write(buf, 0, n)
						digest.update(buf, 0, n)
					}
				}
			}
			check(temp.length() == modelBytes && digest.digest().joinToString("") { "%02x".format(it) } == sha256)
			check(temp.renameTo(modelFile))
		} finally { temp.delete() }
	}

	fun remove() = runTask { check(modelFile.delete() || !modelFile.exists()) }

	fun summarize(file: File, transcript: String) = runTask {
		val config = EngineConfig(modelPath = modelFile.path, backend = Backend.CPU(), cacheDir = getApplication<Application>().cacheDir.path)
		val result = Engine(config).use { engine ->
			engine.initialize()
			transcript.chunked(800).joinToString("\n\n") { section ->
				engine.createConversation().use { chat ->
					val prompt = "Summarize the following transcript excerpt in its original language. Use concise factual bullets.\n\n$section"
					chat.sendMessage(prompt, extraContext = mapOf("enable_thinking" to false)).toString().trim().also { check(it.isNotEmpty()) }
				}
			}
		}
		val target = summaryFile(file, transcript)
		val temp = File.createTempFile("summary-", ".part", target.parentFile)
		try { temp.writeText(result); check(temp.renameTo(target)) } finally { temp.delete() }
	}
}

private fun summaryFile(file: File, transcript: String): File {
	val hash = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray()).joinToString("") { "%02x".format(it) }
	return File(file.parentFile, "${file.nameWithoutExtension}-$hash.summary")
}

@Composable
internal fun SummaryModelPanel(local: LocalSummary) {
	TextButton(enabled = !local.busy, onClick = { if (local.installed) local.remove() else local.download() }) {
		Text(stringResource(if (local.installed) R.string.remove_model else R.string.download_model))
	}
	if (local.busy) LinearProgressIndicator()
	if (local.failed) Text(stringResource(R.string.summary_error), color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun SummaryAction(local: LocalSummary, file: File, transcript: String) {
	if (transcript.isBlank()) return
	val summary by produceState("", file, transcript, local.revision) {
		value = withContext(Dispatchers.IO) { summaryFile(file, transcript).takeIf { it.exists() }?.readText().orEmpty() }
	}
	if (local.installed) TextButton(enabled = !local.busy, onClick = { local.summarize(file, transcript) }) { Text(stringResource(R.string.summarize)) }
	if (summary.isNotEmpty()) Text(summary)
}
