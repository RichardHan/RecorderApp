package io.github.richardhan.voicenote

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class MeetingTool(val label: Int, val instruction: String) {
	Summary(R.string.meeting_summary, "Summarize the important points."),
	Actions(R.string.meeting_actions, "List agreed action items. Include owners and due dates only when stated."),
	Decisions(R.string.meeting_decisions, "List decisions actually made. Exclude proposals and unresolved questions."),
	Chapters(R.string.meeting_chapters, "Produce chronological topic headings anchored to the first passage of each chapter."),
	Translation(R.string.meeting_translation, "Translate every numbered passage, one result per passage in order."),
	Outline(R.string.meeting_outline, "Organize key points under short thematic headings in the group field."),
	Cleanup(R.string.meeting_cleanup, "Edit every passage into readable prose without changing facts or adding information. Return one result per passage in order."),
	Title(R.string.meeting_title, "Suggest one short factual title for the recording.")
}

internal data class MeetingPassage(val text: String, val ms: Long?)
internal data class MeetingPoint(val text: String, val source: Int?, val group: String = "", val quote: String = "", val manual: Boolean = false)
internal data class CheckedTask(val text: String, val done: Boolean)
internal data class MeetingOutput(val points: List<MeetingPoint>, val option: String = "")

internal fun meetingPassages(file: File, transcript: String): List<MeetingPassage> {
	val timing = readTiming(file)
	// Partial provider timings cannot stand in for the full (possibly edited) transcript.
	fun compact(text: String) = text.filterNot(Char::isWhitespace)
	if (timing.isNotEmpty() && compact(timing.joinToString("") { it.text }) == compact(transcript)) {
		return timing.map { MeetingPassage(it.text, (it.start * 1000).toLong()) }
	}
	return transcriptPassages(transcript).map { MeetingPassage(it, null) }
}

internal fun meetingBatches(tool: MeetingTool, passages: List<MeetingPassage>): List<List<MeetingPassage>> {
	if (tool != MeetingTool.Translation && tool != MeetingTool.Cleanup) return listOf(passages)
	val batches = mutableListOf<List<MeetingPassage>>()
	var batch = mutableListOf<MeetingPassage>()
	var length = 0
	for (passage in passages) {
		if (batch.isNotEmpty() && length + passage.text.length > 4000) {
			batches += batch
			batch = mutableListOf()
			length = 0
		}
		batch.add(passage)
		length += passage.text.length
	}
	if (batch.isNotEmpty()) batches += batch
	return batches
}

internal fun meetingInstruction(tool: MeetingTool, option: String, language: String) =
	"""Use the numbered passages as quoted evidence. ${tool.instruction}
		Reply in ${if (tool == MeetingTool.Translation) option else language}. Style: $option.
		Return only a JSON array of objects, each with text, source (one-based passage number), quote (exact nonempty substring of that passage) and optional group. Do not invent source text, people, decisions or times. Return [] if nothing qualifies.""".trimIndent()

internal fun parseMeetingOutput(tool: MeetingTool, raw: String, passages: List<MeetingPassage>, option: String): MeetingOutput {
	val body = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
	val rows = JSONArray(body)
	val points = (0 until rows.length()).map { i ->
		val row = rows.getJSONObject(i)
		val text = (row.get("text") as String).trim().also { require(it.isNotBlank()) }
		val index = row.optInt("source", 0) - 1
		val quote = row.optString("quote").trim()
		val verified = index.takeIf { it in passages.indices && quote.isNotEmpty() && passages[it].text.contains(quote) }
		MeetingPoint(text, verified, row.optString("group"), if (verified != null) quote else "")
	}
	when (tool) {
		MeetingTool.Translation, MeetingTool.Cleanup -> require(points.map { it.source } == passages.indices.toList())
		MeetingTool.Chapters -> require(points.all { it.source != null } && points.mapNotNull { it.source }.zipWithNext().all { (a,b) -> a < b })
		MeetingTool.Title -> require(points.size == 1)
		else -> Unit
	}
	return MeetingOutput(points, option)
}

internal fun meetingDocument(tool: MeetingTool, result: MeetingOutput, passages: List<MeetingPassage>): String = buildString {
	var previousGroup = ""
	for (point in result.points) {
		if (point.group.isNotBlank() && point.group != previousGroup) {
			appendLine("## " + point.group)
			previousGroup = point.group
		}
		val passage = point.source?.let(passages::getOrNull)
		passage?.ms?.let { appendLine(formatClipTime(it.toInt())) }
		if (tool == MeetingTool.Translation && passage != null) appendLine(passage.text)
		appendLine(point.text)
		appendLine()
	}
}.trimEnd()

internal val MeetingTool.usesSavedSummary: Boolean
	get() = this == MeetingTool.Outline || this == MeetingTool.Title

internal fun summaryTitle(text: String): String = text.lineSequence()
	.map { it.trim().trimStart('#', '-', '*', '•', ' ').trim() }
	.firstOrNull { it.any(Char::isLetterOrDigit) }.orEmpty().take(120)

internal class MeetingNotes(private val recording: File, transcript: String) {
	private val digest = transcriptDigest(transcript).toHexString()
	private fun target(key: String) = File(recording.parentFile, "${recording.name}.$digest.$key.meeting")
	private fun resultKey(tool: MeetingTool, option: String) = if (tool == MeetingTool.Translation)
		tool.name + "-" + transcriptDigest(option).toHexString() else tool.name

	fun summaries(): List<File> {
		val prefix = recording.name + "." + digest + ".Summary."
		return recording.parentFile?.listFiles().orEmpty().filter { it != target(MeetingTool.Summary.name) && it.name.startsWith(prefix) && it.name.endsWith(".meeting") }.sortedByDescending { it.name }
	}
	fun discard(tool: MeetingTool, option: String) { val f = target(resultKey(tool, option)); check(!f.exists() || f.delete()) }
	fun discardDraft() { check(!draftFile.exists() || draftFile.delete()) }

	fun save(tool: MeetingTool, result: MeetingOutput) {
		val rows = JSONArray()
		for (point in result.points) rows.put(JSONObject().put("text", point.text).put("source", point.source)
			.put("group", point.group).put("quote", point.quote).put("manual", point.manual))
		val target = target(resultKey(tool, result.option))
		if (tool == MeetingTool.Summary && target.exists()) writeSidecar(target("Summary." + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID()), target.readText())
		writeSidecar(target, JSONObject().put("option", result.option).put("points", rows).toString())
	}

	fun read(tool: MeetingTool, option: String = ""): MeetingOutput? {
		val file = target(resultKey(tool, option))
		if (!file.isFile) return null
		return readOutput(file)
	}

	fun readOutput(file: File): MeetingOutput {
		val doc = JSONObject(file.readText())
		val rows = doc.getJSONArray("points")
		return MeetingOutput((0 until rows.length()).map { i ->
			val row = rows.getJSONObject(i)
			MeetingPoint(row.getString("text"), row.optInt("source", -1).takeIf { it >= 0 }, row.optString("group"), row.optString("quote"), row.optBoolean("manual"))
		}, doc.getString("option"))
	}

	private val draftFile get() = File(recording.parentFile, recording.name + ".draft.meeting")
	fun draft(): Pair<String, String>? {
		if (!draftFile.isFile) return null
		val data = JSONObject(draftFile.readText())
		return data.getString("body") to data.getString("sources")
	}
	fun saveDraft(body: String, sources: String) {
		require(body.isNotBlank())
		writeSidecar(draftFile, JSONObject().put("body", body).put("sources", sources).toString())
	}
	// Accepted tasks belong to the recording and survive transcript corrections.
	private val taskFile get() = File(recording.parentFile, recording.name + ".tasks.meeting")
	fun tasks(): List<CheckedTask> {
		if (!taskFile.isFile) return emptyList()
		val data = JSONArray(taskFile.readText())
		return (0 until data.length()).map { data.getJSONObject(it).let { row -> CheckedTask(row.getString("text"), row.getBoolean("done")) } }
	}
	fun updateTasks(change: (List<CheckedTask>) -> List<CheckedTask>) = synchronized(taskLock) {
		val data = JSONArray()
		for (task in change(tasks()).distinctBy { it.text }) data.put(JSONObject().put("text", task.text).put("done", task.done))
		writeSidecar(taskFile, data.toString())
	}
	private companion object { val taskLock = Any() }
}
