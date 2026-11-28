package io.github.richardhan.voicenote

import android.annotation.SuppressLint
import android.media.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** The encoder and recognizer share the same captured PCM. Recognition can lag without dropping recorded audio. */
internal class LiveCapture(private val temp: File) {
	@Volatile private var running = false
	@Volatile private var paused = false
	@Volatile private var written = 0L
	@Volatile private var failed = false
	@Volatile var peak = 0
		private set
	private var source: AudioRecord? = null
	private var writer: Thread? = null
	internal data class Input(val startByte: Long, val service: SpeechConnection?)
	@Volatile private var localInput: Input? = null
	@Volatile private var cloudInput: Input? = null
	fun switchLocal(on: Boolean) { localInput = if (on) Input(written, null) else null }
	fun switchCloud(service: SpeechConnection?) { cloudInput = service?.let { Input(written, it) } }
	fun reader() = RandomAccessFile(temp, "r")
	val published = CompletableDeferred<Boolean>()

	@SuppressLint("MissingPermission")
	fun start() {
		val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
		require(minimum > 0)
		val mic = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(16384, minimum * 2))
		source = mic
		check(mic.state == AudioRecord.STATE_INITIALIZED)
		mic.startRecording()
		check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
		running = true
		writer = Thread({
			try {
				RandomAccessFile(temp, "rw").use { out ->
					writeWaveHeader(out, 0)
					val samples = ShortArray(1024)
					val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
					while (running) {
						if (paused) { Thread.sleep(20); continue }
						val count = mic.read(samples, 0, samples.size)
						if (!running || paused) continue
						check(count >= 0)
						bytes.clear()
						var max = 0
						for (i in 0 until count) {
							max = maxOf(max, abs(samples[i].toInt()))
							bytes.putShort(samples[i])
						}
						out.write(bytes.array(), 0, count * 2)
						written += count * 2L
						peak = max
					}
					writeWaveHeader(out, written)
				}
			} catch (_: Exception) { failed = true }
		}, "studio-pcm").apply { start() }
	}

	fun pause(value: Boolean) {
		val mic = requireNotNull(source)
		if (value) { paused = true; mic.stop(); peak = 0 }
		else { mic.startRecording(); paused = false }
	}

	fun stop() {
		running = false
		val mic = source
		if (mic != null) {
			try { if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic.stop() }
			finally { writer?.join(3000); mic.release(); source = null }
		}
		check(writer?.isAlive != true && !failed && written > 0)
	}

	fun abort() {
		published.complete(false)
		runCatching { stop() }
	}

	suspend fun recognize(input: RandomAccessFile, models: OfflineSpeechModels, cloud: Boolean, target: File,
		onText: suspend (String) -> Unit, onFailure: suspend () -> Unit) {
		input.use {
			var decoder: SpeechDecoder? = null
			var active: Input? = null
			var offset = 0L
			val lines = mutableListOf<String>()
			val segments = JSONArray()
			val startedAt = android.os.SystemClock.elapsedRealtime()
			var label = ""
			var requests = 0
			fun mode() = if (cloud) cloudInput else localInput
			try {
				while (true) {
					currentCoroutineContext().ensureActive()
					val finished = !running && writer?.isAlive != true
					val enabled = mode()
					if (enabled == null) {
						active = null
						decoder?.close(); decoder = null
						if (finished) break
						delay(160); continue
					}
					if (active !== enabled) { active = enabled; offset = enabled.startByte }
					val service = enabled.service
					val chunk = (service?.liveSeconds ?: 8) * 32000L
					val remaining = written - offset
					if (finished && remaining == 0L) break
					if (remaining < chunk && !finished) { delay(160); continue }
					val overlap = minOf(offset - enabled.startByte, (service?.overlapSeconds ?: 0) * 32000L)
					val count = minOf(remaining, chunk)
					val bytes = ByteArray((count + overlap).toInt())
					input.seek(44 + offset - overlap)
					input.readFully(bytes)
					val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
					val samples = FloatArray(bytes.size / 2) { shorts.get() / 32768f }
					val start = offset / 32000.0
					offset += count
					try {
						if (mode() !== enabled) continue
						val text = if (service == null) {
							val recognizer = decoder ?: models.recognizer().also { decoder = it }
							label = models.selected.label
							recognizer.words(samples)
						} else {
							label = service.model
							requests++
							val previous = lines.lastOrNull().orEmpty()
							val raw = service.liveWords(samples, previous)
							if (overlap == 0L) raw else trimOverlap(previous, raw)
						}
						if (mode() !== enabled || text.isBlank()) continue
						lines.add(text)
						segments.put(JSONObject().put("start", start).put("end", offset / 32000.0).put("text", text))
						onText(text)
						if (service != null && service.correctLive && service.canAnalyze() && mode() === enabled) {
							val corrected = service.textCompletion(text, "Correct transcription punctuation and obvious spelling only. Keep all languages and the same words where uncertain.")
							if (mode() === enabled && corrected.isNotBlank()) {
								lines[lines.lastIndex] = corrected
								segments.getJSONObject(segments.length() - 1).put("text", corrected)
							}
						}
					} catch (e: CancellationException) { throw e }
					catch (_: Exception) { onFailure() }
				}
				if (published.await() && !failed && lines.isNotEmpty()) saveTranscription(target, lines.joinToString("\n"),
					TranscriptionReceipt("$label · live", android.os.SystemClock.elapsedRealtime() - startedAt, if (cloud) null else "0", requests = requests), segments)
			} finally { decoder?.close() }
		}
	}
}

internal fun trimOverlap(previous: String, next: String): String {
	val max = minOf(previous.length, next.length, 400)
	for (n in max downTo 3) if (previous.endsWith(next.take(n), ignoreCase = true)) return next.drop(n).trimStart()
	return next
}

internal fun repeatedSpeech(text: String): Boolean {
	val tokens = Regex("""[\p{IsHan}]|[\p{L}\p{N}]+""").findAll(text.lowercase()).map { it.value }.toList()
	if (tokens.size < 12) return false
	return (1..minOf(16, tokens.size / 4)).any { period ->
		(period until tokens.size).count { tokens[it] == tokens[it - period] } >= (tokens.size - period) * 0.9
	}
}
