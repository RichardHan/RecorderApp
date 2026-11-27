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
	private val reader = RandomAccessFile(temp, "r")
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
		reader.close()
	}

	suspend fun recognize(models: OfflineSpeechModels, target: File, onText: suspend (String) -> Unit) {
		reader.use { input ->
			val recognizer = models.recognizer()
			try {
				val segments = JSONArray()
				val lines = mutableListOf<String>()
				val startTime = android.os.SystemClock.elapsedRealtime()
				val chunk = 8 * 32000
				var offset = 0L
				while (true) {
					currentCoroutineContext().ensureActive()
					val remaining = written - offset
					val finished = !running && writer?.isAlive != true
					if (finished && remaining == 0L) break
					if (remaining < chunk && !finished) { delay(200); continue }
					val bytes = ByteArray(minOf(remaining, chunk.toLong()).toInt())
					input.seek(44 + offset)
					input.readFully(bytes)
					val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
					val text = recognizeChunk(recognizer, FloatArray(bytes.size / 2) { shorts.get() / 32768f })
					val start = offset / 32000.0
					offset += bytes.size
					if (text.isNotBlank()) {
						lines.add(text)
						segments.put(JSONObject().put("start", start).put("end", offset / 32000.0).put("text", text))
						onText(text)
					}
				}
				if (published.await() && !failed && lines.isNotEmpty()) saveTranscription(target, lines.joinToString("\n"),
					TranscriptionReceipt("SenseVoice · live", android.os.SystemClock.elapsedRealtime() - startTime, "0"), segments)
			} finally { recognizer.release() }
		}
	}
}
