package io.github.richardhan.voicenote

import android.os.SystemClock
import androidx.compose.runtime.*
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

private const val OPEN_ROUTER = "https://openrouter.ai/api/v1"
private data class SpeechResponse(val body: JSONObject, val gatewayCost: String?)
private fun JSONObject.costUsd() = reportedCostUsd(optJSONObject("usage")?.optString("cost"))
internal class HttpFailure(val code: Int) : Exception()

class SpeechConnection internal constructor(app: Application, private val open: HttpsOpen) : AndroidViewModel(app) {
	constructor(app: Application) : this(app, ::openHttps)
	private val vault = ConnectionVault(app, "studio-speech")
	var custom by mutableStateOf(false)
		private set
	var base by mutableStateOf(OPEN_ROUTER)
		private set
	var key by mutableStateOf("")
	var model by mutableStateOf("openai/whisper-large-v3-turbo")
	var models by mutableStateOf(emptyList<String>())
		private set
	var busy by mutableStateOf(false)
		private set
	var error by mutableIntStateOf(0)
		private set
	var httpStatus by mutableIntStateOf(0)
		private set
	var revision by mutableIntStateOf(0)
		private set

	internal fun recognize(file: File, models: OfflineSpeechModels) = request {
		withContext(Dispatchers.IO) { models.recognize(file) }
		revision++
	}

	internal fun edit(file: File, text: String) = request {
		require(text.isNotBlank())
		withContext(Dispatchers.IO) { archiveTranscript(file); writeSidecar(transcriptFile(file), text) }
		revision++
	}

	var chatModel by mutableStateOf("")
	internal var separateText by mutableStateOf(false)
	internal var textBase by mutableStateOf("")
		private set
	internal var textKey by mutableStateOf("")
	internal var textGemini by mutableStateOf(false)
	internal val textDestination get() = if (separateText) textBase else base
	private val textSecret get() = if (separateText) textKey else key
	private val nativeText get() = if (separateText) textGemini else protocol == SpeechProtocol.Gemini
	internal fun canReuseKey(): Boolean {
		val audio = endpoint() ?: return false
		val text = endpoint(textBase) ?: return false
		fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
		return key.isNotBlank() && audio.host.equals(text.host, true) && port(audio) == port(text)
	}
	internal fun reuseKey() { require(canReuseKey()); textKey = key }
	internal fun setTextAddress(address: String) {
		if (address != textBase) { textBase = address; textKey = "" }
	}
	internal var probeResult by mutableStateOf("")
		private set
	internal fun probe(summary: Boolean) = request {
		require(hasSavedConnection && savedProfile == profileId())
		probeResult = ""
		val send = if (summary) textClient() else textClient(base, key, model, protocol == SpeechProtocol.Gemini)
		val answer = withContext(Dispatchers.IO) { send("Studio connection check", "Reply with OK.") }
		probeResult = answer.take(160)
	}
	private val prices = app.getSharedPreferences("studio_model_prices", 0)
	private fun priceKey(address: String = textDestination, id: String = chatModel) = transcriptDigest(address.trimEnd('/') + "\u0000" + id.trim()).toHexString()
	internal fun chatPrice(output: Boolean = false): String = prices.getString(priceKey() + if (output) "/out" else "", "").orEmpty()
	internal fun setChatPrice(value: String, output: Boolean = false) {
		require(value.isBlank() || reportedCostUsd(value) != null)
		prices.edit().putString(priceKey() + if (output) "/out" else "", value.trim()).apply()
	}
	internal var effort by mutableStateOf("")
	internal var speakerLabels by mutableStateOf(false)
	internal fun effectiveEffort(id: String, transcription: Boolean = false): String? {
		if ((if (transcription) protocol == SpeechProtocol.Gemini else nativeText) || effort !in setOf("low", "medium", "high") || id.isBlank()) return null
		val name = id.lowercase()
		if ("transcribe" in name || "flash-lite" in name) return null
		if (transcription && (protocol != SpeechProtocol.Chat || speakerLabels || name.endsWith("gemini-4-argon"))) return null
		return effort
	}
	private fun applyEffort(body: JSONObject, value: String?, customEndpoint: Boolean = custom): JSONObject {
		if (value != null) {
			if (customEndpoint) body.put("reasoning_effort", value)
			else body.put("reasoning", JSONObject().put("effort", value))
		}
		return body
	}
	internal var protocol by mutableStateOf(SpeechProtocol.Multipart)
	var vocabulary by mutableStateOf("")
	var segmentSeconds by mutableIntStateOf(20)
	var concurrentRequests by mutableIntStateOf(1)
	var skipSilence by mutableStateOf(false)
	internal var liveSeconds by mutableIntStateOf(60)
	internal var overlapSeconds by mutableIntStateOf(3)
	internal var highlightSeconds by mutableIntStateOf(60)
	internal var highlightPrompt by mutableStateOf("")
	internal var cloudFirst by mutableStateOf(false)
	internal var firstHighlightSeconds by mutableIntStateOf(0)
	internal var correctLive by mutableStateOf(false)
	internal var warnLength by mutableStateOf(true)
	internal var compressAudio by mutableStateOf(false)
	internal var retrySegments by mutableStateOf(false)
	internal var defaultSummary by mutableStateOf("")
	internal var summaryNotes by mutableStateOf(false)
	internal var defaultCloud by mutableStateOf(false)
	private var running: kotlinx.coroutines.Job? = null
	fun cancel() { running?.cancel() }


	var savedProfile by mutableStateOf("")
		private set
	var hasSavedConnection by mutableStateOf(false)
		private set
	init {
		try {
			vault.load()?.let {
				custom = it.getBoolean("custom")
				base = it.getString("base")
				key = it.getString("key")
				model = it.getString("model")
				chatModel = it.optString("chatModel")
				separateText = it.optBoolean("separateText")
				textBase = it.optString("textBase")
				textKey = it.optString("textKey")
				textGemini = it.optBoolean("textGemini")
				effort = it.optString("effort")
				speakerLabels = it.optBoolean("speakerLabels")
				protocol = SpeechProtocol.entries.firstOrNull { mode -> mode.name == it.optString("protocol") } ?: SpeechProtocol.Multipart
				vocabulary = it.optString("vocabulary")
				segmentSeconds = it.optInt("seconds", 20).coerceIn(10, 300)
				concurrentRequests = it.optInt("concurrent", 1).coerceIn(1, 6)
				skipSilence = it.optBoolean("skipSilence")
				liveSeconds = it.optInt("liveSeconds", 60).coerceIn(15,600)
				overlapSeconds = it.optInt("overlap", 3).coerceIn(0,5)
				highlightSeconds = it.optInt("highlights", 60).coerceIn(15,600)
				highlightPrompt = it.optString("highlightPrompt")
				cloudFirst = it.optBoolean("cloudFirst")
				firstHighlightSeconds = it.optInt("first", 0).coerceIn(0,600)
				correctLive = it.optBoolean("correctLive")
				warnLength = it.optBoolean("warnLength", true)
				compressAudio = it.optBoolean("mp3")
				retrySegments = it.optBoolean("retrySegments")
				defaultSummary = it.optString("defaultSummary")
				summaryNotes = it.optBoolean("summaryNotes")
				defaultCloud = it.optBoolean("defaultCloud")
				hasSavedConnection = true
				savedProfile = profileId()
			}
		} catch (_: Exception) { error = R.string.connection_error }
	}
	fun saveConnection() = request {
		require(canSave())
		val data = JSONObject().put("custom", custom).put("base", base).put("key", key).put("model", model).put("chatModel", chatModel).put("protocol", protocol.name).put("vocabulary", vocabulary)
			.put("separateText", separateText).put("textBase", textBase).put("textKey", textKey).put("textGemini", textGemini)
			.put("effort", effort).put("speakerLabels", speakerLabels)
			.put("seconds", segmentSeconds).put("concurrent", concurrentRequests).put("skipSilence", skipSilence)
		data.put("highlightPrompt", highlightPrompt).put("cloudFirst",cloudFirst).put("liveSeconds", liveSeconds).put("overlap", overlapSeconds).put("highlights", highlightSeconds).put("first", firstHighlightSeconds)
			.put("mp3", compressAudio).put("correctLive", correctLive).put("warnLength", warnLength).put("retrySegments", retrySegments)
			.put("defaultSummary", defaultSummary).put("summaryNotes", summaryNotes).put("defaultCloud", defaultCloud)
		withContext(Dispatchers.IO) { vault.save(data) }
		hasSavedConnection = true
		savedProfile = profileId()
	}
	fun forgetConnection() = request {
		withContext(Dispatchers.IO) { vault.remove() }
		key = ""
		textKey = ""
		probeResult = ""
		hasSavedConnection = false
		savedProfile = ""
	}

	internal fun editTimed(file: File, expected: List<TimedText>, index: Int, replacement: String) = request {
		withContext(Dispatchers.IO) {
			check(expected == readTiming(file) && index in expected.indices)
			val source = readTranscript(file)
			val current = expected.joinToString("\n") { it.text }
			check(current.filterNot(Char::isWhitespace) == source.filterNot(Char::isWhitespace))
			val updated = expected.toMutableList()
			updated[index] = updated[index].copy(text = replacement.trim().also { require(it.isNotEmpty()) })
			val rows = org.json.JSONArray()
			for (line in updated) rows.put(JSONObject().put("start",line.start).put("end",line.end).put("text",line.text))
			saveTranscription(file, updated.joinToString("\n") { it.text }, loadSavedTranscript(file).receipt, rows)
		}
		revision++
	}

	internal fun removeQuestion(file: File, transcript: String, index: Int, expected: String) = request {
		withContext(Dispatchers.IO) {
			val target = chatFile(file, transcript)
			val history = readMessages(target)
			check(history.getJSONObject(index).toString() == expected)
			history.remove(index)
			writeSidecar(target, history.toString())
		}
		revision++
	}

	internal fun clearChat(file: File, transcript: String) = request {
		withContext(Dispatchers.IO) {
			val target = chatFile(file, transcript)
			check(!target.exists() || target.delete())
		}
		revision++
	}

	internal fun ask(file: File, transcript: String, question: String, mask: Boolean = false, summary: String = "") {
		val server = endpoint(textDestination)
		if (server == null || textSecret.isBlank() || chatModel.isBlank()) { error = R.string.invalid; return }
		val modelId = chatModel.trim()
		val token = textSecret.trim()
		val gemini = nativeText
		val reasoning = effectiveEffort(modelId)
		val customText = separateText || custom
		request {
			withContext(Dispatchers.IO) {
				val target = chatFile(file, transcript)
				val history = readMessages(target)
				val payload = chatPayload(modelId, transcript, history, question, mask, summary)
				val response = if (gemini) {
					val messages = payload.getJSONArray("messages")
					val content = org.json.JSONArray()
					for (i in 1 until messages.length()) {
						val msg = messages.getJSONObject(i)
						val text = msg.getString("content")
						content.put(JSONObject().put("role", if (msg.getString("role") == "assistant") "model" else "user")
							.put("parts", org.json.JSONArray().put(JSONObject().put("text", text))))
					}
					val body = JSONObject().put("contents", content).put("systemInstruction",
						JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", messages.getJSONObject(0).getString("content")))))
					jsonRequest("$server/models/${android.net.Uri.encode(modelId)}:generateContent", token, body, true)
				} else jsonRequest("$server/chat/completions", token, applyEffort(payload, reasoning, customText))
				val answer = generatedText(if (gemini) SpeechProtocol.Gemini else SpeechProtocol.Chat, response)
				require(answer.isNotBlank())
				kotlinx.coroutines.currentCoroutineContext().ensureActive()
				history.put(JSONObject().put("question", question).put("answer", answer).put("model", modelId).put("sourceLength", chatExcerpt(transcript).length))
				writeSidecar(target, history.toString())
			}
			revision++
		}
	}

	internal fun canAnalyze() = endpoint(textDestination) != null && textSecret.isNotBlank() && chatModel.isNotBlank()

	internal fun generateMeetingNotes(file: File, transcript: String, tool: MeetingTool, option: String, language: String, mask: Boolean) = request {
		require(canAnalyze())
		val complete = textClient()
		withContext(Dispatchers.IO) {
			val notes = MeetingNotes(file, transcript)
			val summaryPoints = if (tool.usesSavedSummary) notes.read(MeetingTool.Summary)?.points else null
			val plainSummary = if (tool.usesSavedSummary && summaryPoints.isNullOrEmpty()) summaryFile(file, transcript).takeIf(File::isFile)?.readText().orEmpty() else ""
			val evidence = if (!tool.usesSavedSummary) meetingPassages(file, transcript)
				else (summaryPoints?.map { MeetingPassage(it.text, null) }?.takeIf { it.isNotEmpty() } ?: transcriptPassages(plainSummary).map { MeetingPassage(it, null) })
			require(evidence.isNotEmpty())
			val agenda = if (summaryNotes && tool == MeetingTool.Summary) withContext(Dispatchers.Main) { (getApplication<Application>() as StudioApplication).library.catalog.value(file, "notes") } else ""
			val passages = evidence.map { if (mask) it.copy(text = maskIdentifiers(it.text)) else it }
			val points = mutableListOf<MeetingPoint>()
			var offset = 0
			for (batch in meetingBatches(tool, passages)) {
				kotlinx.coroutines.currentCoroutineContext().ensureActive()
				val input = batch.mapIndexed { i, passage -> "[${i + 1}] ${passage.text}" }.joinToString("\n")
				val context = if (agenda.isBlank()) input else input + "\nAgenda (reference, not evidence):\n" + if (mask) maskIdentifiers(agenda) else agenda
				val answer = complete(context, meetingInstruction(tool, option, language))
				points += parseMeetingOutput(tool, answer, batch, option).points.map { point ->
					val index = point.source?.plus(offset)
					if (tool.usesSavedSummary) {
						val cited = index?.let { summaryPoints?.getOrNull(it) }
						point.copy(source = cited?.source, quote = cited?.quote.orEmpty())
					} else point.copy(source = index)
				}
				offset += batch.size
			}
			val result = MeetingOutput(points, option)
			kotlinx.coroutines.currentCoroutineContext().ensureActive()
			check(file.exists() && readTranscript(file) == transcript)
			MeetingNotes(file, transcript).save(tool, result)
		}
		revision++
	}

	internal fun followUp(file: File, transcript: String, materials: String, instructions: String) = request {
		require(canAnalyze() && materials.isNotBlank())
		withContext(Dispatchers.IO) {
			val answer = textCompletion(materials, "Draft a follow-up message in the language of the materials. Use only the supplied summary and accepted tasks. Never invent owners, dates or promises. Format preference: " + instructions)
			kotlinx.coroutines.currentCoroutineContext().ensureActive()
			check(file.exists())
			MeetingNotes(file, transcript).saveDraft(answer, transcriptDigest(materials).toHexString())
		}
		revision++
	}
	internal fun editDraft(file: File, transcript: String, body: String, sourceDigest: String) = request {
		withContext(Dispatchers.IO) { check(file.exists()); MeetingNotes(file, transcript).saveDraft(body, sourceDigest) }
		revision++
	}

	internal fun reviseDecision(file: File, transcript: String, original: MeetingPoint?, replacement: String?) = request {
		withContext(Dispatchers.IO) {
			val notes = MeetingNotes(file, transcript)
			val current = notes.read(MeetingTool.Decisions) ?: MeetingOutput(emptyList())
			val index = current.points.indexOf(original)
			check(original == null || index >= 0)
			val points = current.points.toMutableList()
			if (original == null) points.add(MeetingPoint(requireNotNull(replacement), null))
			else if (replacement == null) points.removeAt(index) else points[index] = original.copy(text = replacement)
			notes.save(MeetingTool.Decisions, current.copy(points = points))
		}
		revision++
	}

	internal fun discardResult(file: File, transcript: String, tool: MeetingTool?, option: String) = request {
		withContext(Dispatchers.IO) {
			val notes = MeetingNotes(file, transcript)
			if (tool == null) notes.discardDraft() else notes.discard(tool, option)
		}
		revision++
	}

	internal fun summarizeArchive(dir: File, option: String) = request {
		val complete = textClient()
		withContext(Dispatchers.IO) {
			val source = File(dir, "text.txt").readText()
			val result = complete(source, "Summarize this transcript in its original language. $option")
			kotlinx.coroutines.currentCoroutineContext().ensureActive()
			writeSidecar(File(dir, "summary-" + System.currentTimeMillis() + ".txt"), result)
		}
		revision++
	}

	internal fun changeMeetingTasks(file: File, transcript: String, change: (List<CheckedTask>) -> List<CheckedTask>) = request {
		withContext(Dispatchers.IO) { check(file.exists()); MeetingNotes(file, transcript).updateTasks(change) }
		revision++
	}

	fun changeBase(value: String) {
		base = value
		key = ""
	}
	fun setCustomMode(value: Boolean) {
		custom = value
		protocol = SpeechProtocol.Multipart
		changeBase(if (custom) "" else OPEN_ROUTER)
		model = if (custom) "" else "openai/whisper-large-v3-turbo"
		models = emptyList()
		error = 0
	}

	private fun endpoint(address: String = base): URI? = runCatching { URI(address.trim().trimEnd('/')) }.getOrNull()?.takeIf {
		it.scheme.equals("https", ignoreCase = true) && it.host != null && it.userInfo == null &&
			it.query == null && it.fragment == null
	}
	internal fun canSave() = (valid() || (separateText && canAnalyze())) && (!separateText || canAnalyze())
	fun valid(): Boolean = endpoint() != null && model.isNotBlank() && key.isNotBlank()

	private fun request(block: suspend () -> Unit) {
		if (busy) return
		busy = true
		error = 0
		running = viewModelScope.launch {
			try { block() }
			catch (e: CancellationException) { throw e }
			catch (_: LinkageError) { error = R.string.offline_error }
			catch (e: HttpFailure) { httpStatus = e.code; error = R.string.http_error }
			catch (_: Exception) { error = R.string.failed }
			finally { busy = false }
		}
	}

	fun loadModels() = request {
		models = withContext(Dispatchers.IO) {
			val response = exchange("$OPEN_ROUTER/models?output_modalities=transcription", "")
			val data = response.body.getJSONArray("data")
			(0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
		}
	}

	internal fun youtube(file: File, link: String, chosen: String) {
		val token = key.trim()
		if (custom || token.isEmpty() || chosen.isBlank()) { error = R.string.invalid; return }
		request {
			withContext(Dispatchers.IO) {
				val content = org.json.JSONArray()
					.put(JSONObject().put("type", "input_text").put("text", "Transcribe all speech verbatim in its original languages. Return only the transcript."))
					.put(JSONObject().put("type", "input_video").put("video_url", link))
				val input = org.json.JSONArray().put(JSONObject().put("role", "user").put("content", content))
				val payload = JSONObject().put("model", chosen).put("input", input).toString().toByteArray()
				val started = SystemClock.elapsedRealtime()
				val response = exchange("$OPEN_ROUTER/responses", token, chosen, OPEN_ROUTER) { conn ->
					conn.requestMethod = "POST"
					conn.doOutput = true
					conn.setRequestProperty("Content-Type", "application/json")
					conn.outputStream.use { it.write(payload) }
				}
				saveVideoTranscription(file, response.body, chosen, SystemClock.elapsedRealtime() - started)
			}
			revision++
		}
	}

	fun transcribe(file: File, timestamps: Boolean) {
		if (!valid()) { error = R.string.invalid; return }
		request {
			withContext(Dispatchers.IO) { transcribeNow(file, timestamps) }
			revision++
		}
	}

	internal suspend fun transcribeNow(file: File, timestamps: Boolean) {
		require(valid())
		val server = requireNotNull(endpoint())
		val token = key.trim()
		val chosen = model.trim()
		val mode = protocol
		val spelling = vocabulary
		if (mode != SpeechProtocol.Multipart) {
			val gemini = mode == SpeechProtocol.Gemini
			val target = if (gemini) "$server/models/${android.net.Uri.encode(chosen)}:generateContent" else "$server/chat/completions"
			val reasoning = effectiveEffort(chosen, transcription = true)
			val hint = if (speakerLabels) spelling + "\nLabel audible speaker changes with neutral speaker numbers. Never guess personal names." else spelling
			segmentedTranscript(file, server.toString(), chosen, mode, hint, segmentSeconds, concurrentRequests, skipSilence, reasoning, compressAudio, retrySegments, warnLength) { json ->
				jsonRequest(target, token, applyEffort(json, reasoning), gemini)
			}
			return
		}
		val url = "$server/audio/transcriptions"
		val useGatewayCost = custom
		val started = SystemClock.elapsedRealtime()
		val reply = exchange(url, token, chosen, server.toString()) { connection ->
			val boundary = UUID.randomUUID().toString()
			val format = if (timestamps) "--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\nverbose_json\r\n" else ""
			val header = format + "--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n$chosen\r\n" +
				"--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording.${file.extension}\"\r\nContent-Type: ${audioTypes.getValue(file.extension)}\r\n\r\n"
			val hint = if (spelling.isBlank()) "" else "--$boundary\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n$spelling\r\n"
			val prefix = (hint + header).toByteArray()
			val suffix = "\r\n--$boundary--\r\n".toByteArray()
			connection.requestMethod = "POST"
			connection.doOutput = true
			connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
			connection.setFixedLengthStreamingMode(prefix.size + file.length() + suffix.size)
			connection.outputStream.use { out ->
				out.write(prefix)
				file.inputStream().use { it.copyTo(out) }
				out.write(suffix)
			}
		}
		val elapsedMs = SystemClock.elapsedRealtime() - started
		val gatewayCost = if (useGatewayCost) reportedCostUsd(reply.gatewayCost) else null
		val cost = gatewayCost ?: reply.body.costUsd()
		val text = requireNotNull(reply.body.opt("text") as? String)
		val segments = if (timestamps) reply.body.optJSONArray("segments") else null
		saveTranscription(file, text, TranscriptionReceipt(chosen, elapsedMs, cost,
			reply.body.optJSONObject("usage")?.optLong("total_tokens", -1)?.takeIf { it >= 0 }, 1, server.host), segments)
	}

	internal fun liveWords(samples: FloatArray, previous: String): String {
		require(protocol != SpeechProtocol.Multipart && valid())
		val gemini = protocol == SpeechProtocol.Gemini
		val endpoint = requireNotNull(endpoint())
		val url = if (gemini) "$endpoint/models/${android.net.Uri.encode(model.trim())}:generateContent" else "$endpoint/chat/completions"
		val hint = listOf(vocabulary, previous.takeLast(240)).filter(String::isNotBlank).joinToString("\n")
		return generatedText(protocol, jsonRequest(url, key.trim(), audioPayload(protocol, model.trim(), samples, hint), gemini))
	}

	internal fun textCompletion(text: String, instruction: String) = textClient()(text, instruction)

	private fun textClient(address: String = textDestination, credential: String = textSecret, selected: String = chatModel, gemini: Boolean = nativeText): (String, String) -> String {
		val server = requireNotNull(endpoint(address))
		val modelId = selected.trim().also { require(it.isNotEmpty()) }
		val secret = credential.trim().also { require(it.isNotEmpty()) }
		val reasoning = effectiveEffort(modelId)
		val customEndpoint = address.trimEnd('/') != OPEN_ROUTER
		return { text, instruction ->
			val messages = org.json.JSONArray()
			if (gemini) {
				val parts = org.json.JSONArray().put(JSONObject().put("text", text))
				messages.put(JSONObject().put("role", "user").put("parts", parts))
				val system = JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", instruction)))
				val body = JSONObject().put("contents", messages).put("systemInstruction", system)
				generatedText(SpeechProtocol.Gemini, jsonRequest("$server/models/${android.net.Uri.encode(modelId)}:generateContent", secret, body, true))
			} else {
				messages.put(JSONObject().put("role", "system").put("content", instruction))
				messages.put(JSONObject().put("role", "user").put("content", text))
				val body = JSONObject().put("model", modelId).put("messages", messages)
				generatedText(SpeechProtocol.Chat, jsonRequest("$server/chat/completions", secret, applyEffort(body, reasoning, customEndpoint)))
			}
		}
	}

	private fun jsonRequest(url: String, token: String, body: JSONObject, gemini: Boolean = false): JSONObject =
		exchange(url, if (gemini) "" else token,
			if (gemini) android.net.Uri.decode(url.substringAfterLast("/models/").substringBefore(":generateContent")) else body.optString("model"),
			if (gemini) url.substringBeforeLast("/models/") else url.removeSuffix("/chat/completions")) { conn ->
			if (gemini) conn.setRequestProperty("x-goog-api-key", token)
			conn.requestMethod = "POST"
			conn.doOutput = true
			conn.setRequestProperty("Content-Type", "application/json")
			conn.outputStream.use { it.write(body.toString().toByteArray()) }
		}.body


	private fun exchange(url: String, token: String, billingModel: String = "", address: String = "", send: (HttpsURLConnection) -> Unit = {}): SpeechResponse {
		val connection = open(URI(url).toURL())
		try {
			connection.instanceFollowRedirects = false
			connection.connectTimeout = 15000
			connection.readTimeout = 180000
			if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
			send(connection)
			if (connection.responseCode !in 200..299) throw HttpFailure(connection.responseCode)
			val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
			val fee = connection.getHeaderField("x-litellm-response-cost")
			if (connection.requestMethod == "POST") {
				val rate = priceKey(address, billingModel)
				MonthlyUsage(getApplication()).record(body, fee, model = billingModel, inputRate = prices.getString(rate,"").orEmpty(), outputRate = prices.getString("$rate/out","").orEmpty())
			}
			return SpeechResponse(body, fee)
		} finally { connection.disconnect() }
	}
}
internal fun transcriptFile(file: File) = File(file.parentFile, file.nameWithoutExtension + ".txt")

internal fun saveVideoTranscription(file: File, response: JSONObject, model: String, elapsedMs: Long) {
	require(response.optString("status") == "completed")
	val output = response.getJSONArray("output")
	val text = buildString {
		for (i in 0 until output.length()) {
			val parts = output.getJSONObject(i).optJSONArray("content") ?: continue
			for (j in 0 until parts.length()) {
				val part = parts.getJSONObject(j)
				if (part.optString("type") == "output_text") appendLine(part.getString("text"))
			}
		}
	}.trim()
	saveTranscription(file, text, TranscriptionReceipt(model, elapsedMs, response.costUsd()))
}
