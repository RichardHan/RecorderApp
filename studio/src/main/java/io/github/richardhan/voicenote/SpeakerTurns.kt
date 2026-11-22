package io.github.richardhan.voicenote

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileNotFoundException
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private data class SpeakerModel(val name: String, val url: String, val bytes: Long, val hash: String)
private val segmentation = SpeakerModel("segments-v1.onnx",
	"https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.int8.onnx",
	1540506, "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d")
private val embedding = SpeakerModel("voices-v1.onnx",
	"https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
	39593762, "f1ee61be04000b50392a63442e5dd9feaea7e81dddd494d3647cf1c3d88a1498")

internal data class SpeakerTurn(val start: Double, val end: Double, val speaker: Int)
internal data class TimedText(val start: Double, val end: Double, val text: String)

internal fun closestTurn(turns: List<SpeakerTurn>, segment: TimedText): Int? {
	var best = 0.0
	var winner: Int? = null
	for ((index, turn) in turns.withIndex()) {
		val overlap = minOf(turn.end, segment.end) - maxOf(turn.start, segment.start)
		if (overlap > best) {
			best = overlap
			winner = index
		} else if (overlap == best && winner != null && turn.speaker != turns[winner].speaker) winner = null
	}
	return winner
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

internal fun writeSidecar(file: File, data: String) {
	val temp = File.createTempFile("speaker-", ".part", file.parentFile)
	try { temp.writeText(data); check(temp.renameTo(file)) } finally { temp.delete() }
}

internal fun timingFile(file: File) = File(file.parentFile, file.name + ".timing")

internal fun readTiming(file: File): List<TimedText> {
	val data = try { timingFile(file).readText() } catch (_: FileNotFoundException) { return emptyList() }
	val json = JSONObject(data)
	if (json.getString("digest") != transcriptDigest(readTranscript(file)).toHexString()) return emptyList()
	val parts = json.getJSONArray("segments")
	return buildList {
		for (i in 0 until parts.length()) {
			val part = parts.optJSONObject(i) ?: continue
			val start = part.optDouble("start")
			val end = part.optDouble("end")
			val text = part.optString("text").trim()
			if (start >= 0 && end > start && end.isFinite() && text.isNotEmpty()) add(TimedText(start, end, text))
		}
	}
}

private fun turnsFile(file: File) = File(file.parentFile, file.name + ".turns")
internal data class SpeakerTimeline(val turns: List<SpeakerTurn>, val names: Map<Int, String>)

internal fun writeTimelineText(out: Appendable, timeline: SpeakerTimeline, segments: List<TimedText>, speaker: Int?, speakerLabel: (Int) -> String) {
	val groups = segments.groupBy { closestTurn(timeline.turns, it) }
	var first = true
	for ((index, turn) in timeline.turns.withIndex()) {
		if (speaker != null && turn.speaker != speaker) continue
		if (!first) out.appendLine()
		first = false
		out.append(formatClipTime(Math.round(turn.start * 1000).toInt())).append(" – ")
			.append(formatClipTime(Math.round(turn.end * 1000).toInt())).append(" · ")
			.appendLine(timeline.names[turn.speaker] ?: speakerLabel(turn.speaker))
		groups[index]?.forEach { out.appendLine(it.text) }
	}
}

internal fun readTimeline(file: File): SpeakerTimeline? {
	val target = turnsFile(file)
	if (!target.exists()) return null
	val data = JSONObject(target.readText())
	val rows = data.getJSONArray("turns")
	val names = data.optJSONObject("names") ?: JSONObject()
	val turns = List(rows.length()) { i ->
		val row = rows.getJSONArray(i)
		SpeakerTurn(row.getDouble(0), row.getDouble(1), row.getInt(2))
	}
	return SpeakerTimeline(turns, names.keys().asSequence().associate { it.toInt() to names.getString(it) })
}

class SpeakerAnalysis(app: Application) : AndroidViewModel(app) {
	private val dir = File(app.noBackupFilesDir, "speaker-models").apply { mkdirs() }
	private fun available(model: SpeakerModel) = File(dir, model.name).length() == model.bytes
	var installed by mutableStateOf(available(segmentation) && available(embedding))
		private set
	var busy by mutableStateOf(false)
		private set
	var message by mutableIntStateOf(0)
		private set
	var revision by mutableIntStateOf(0)
		private set

	private fun run(failure: Int = R.string.turn_error, success: Int = 0, action: suspend () -> Unit) {
		if (busy) return
		busy = true
		message = 0
		viewModelScope.launch {
			try { withContext(Dispatchers.IO) { action() }; message = success; revision++ }
			catch (e: CancellationException) { throw e }
			catch (_: RecordingTooLong) { message = R.string.turn_limit }
			catch (_: LinkageError) { message = R.string.turn_error }
			catch (_: Exception) { message = failure }
			finally { installed = available(segmentation) && available(embedding); busy = false }
		}
	}

	fun download() = run {
		for (model in listOf(segmentation, embedding)) {
			if (available(model)) continue
			val temp = File.createTempFile("download-", ".part", dir)
			try {
				val digest = MessageDigest.getInstance("SHA-256")
				val conn = URL(model.url).openConnection().apply { connectTimeout = 20000; readTimeout = 45000 }
				conn.getInputStream().use { input -> temp.outputStream().buffered().use { out ->
					val buffer = ByteArray(65536)
					var total = 0L
					while (true) {
						currentCoroutineContext().ensureActive()
						val n = input.read(buffer)
						if (n < 0) break
						total += n
						check(total <= model.bytes)
						out.write(buffer, 0, n)
						digest.update(buffer, 0, n)
					}
				} }
				check(temp.length() == model.bytes && digest.digest().hex() == model.hash)
				check(temp.renameTo(File(dir, model.name)))
			} finally { temp.delete() }
		}
	}
	internal fun export(file: File, uri: Uri, speaker: Int?) = run(R.string.turn_export_error, R.string.turn_exported) {
		val app = getApplication<Application>()
		val timeline = requireNotNull(readTimeline(file))
		require(timeline.turns.isNotEmpty())
		val segments = readTiming(file)
		requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { writer ->
			writeTimelineText(writer, timeline, segments, speaker) { app.getString(R.string.speaker_number, it) }
		}
	}

	fun rename(file: File, speaker: Int, value: String) = run {
		val target = turnsFile(file)
		val data = JSONObject(target.readText())
		val names = data.optJSONObject("names") ?: JSONObject()
		val name = value.trim()
		if (name.isEmpty()) names.remove(speaker.toString()) else names.put(speaker.toString(), name)
		writeSidecar(target, data.put("names", names).toString())
	}

	fun analyze(file: File, knownCount: Int?) = run {
		val samples = decodeForSpeakers(file)
		val config = OfflineSpeakerDiarizationConfig(
			segmentation = OfflineSpeakerSegmentationModelConfig(
				pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = File(dir, segmentation.name).path)),
			embedding = SpeakerEmbeddingExtractorConfig(model = File(dir, embedding.name).path),
			clustering = FastClusteringConfig(numClusters = knownCount ?: -1, threshold = 0.5f),
			minDurationOn = 0.3f, minDurationOff = 0.5f
		)
		val engine = OfflineSpeakerDiarization(config = config)
		val turns = try { engine.process(samples).sortedBy { it.start } } finally { engine.release() }
		val labels = mutableMapOf<Int, Int>()
		val rows = JSONArray()
		for (turn in turns) {
			val speaker = labels.getOrPut(turn.speaker) { labels.size + 1 }
			rows.put(JSONArray().put(turn.start.toDouble()).put(turn.end.toDouble()).put(speaker))
		}
		writeSidecar(turnsFile(file), JSONObject().put("turns", rows).toString())
	}
}
