package com.eva.cloudtranscribe.chapters

import com.eva.cloudtranscribe.model.PlaybackLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One transcript sentence the model may reference; [index] is what it has to answer with. */
data class ChapterSentence(val index: Int, val startMs: Long, val text: String)

/** A chapter before it is stored; [startMs] was resolved locally from the sentence index. */
data class ChapterDraft(val title: String, val startSentenceIndex: Int, val startMs: Long)

/**
 * Builds the chapter request and validates the answer. The model only ever returns sentence indices plus titles;
 * every time stamp is copied from [ChapterSentence.startMs], so a wrong index is rejected instead of producing a
 * fabricated time. Nothing here talks to a network.
 */
object ChapterPlanner {

	const val MAX_CHAPTERS = 12
	const val MAX_TITLE_CHARS = 20

	/** Sentences with a real start time only; untimed transcripts cannot produce chapters. */
	fun sentences(lines: List<PlaybackLine>): List<ChapterSentence> =
		lines.mapIndexedNotNull { index, line ->
			line.startMs?.let { ChapterSentence(index = index, startMs = it, text = line.text.trim()) }
				?.takeIf { it.text.isNotEmpty() }
		}

	fun hasTimedSentences(lines: List<PlaybackLine>): Boolean = lines.any { it.startMs != null }

	/** Numbered transcript + strict output contract: indices only, 3–12 chapters, short titles, no prose. */
	fun prompt(sentences: List<ChapterSentence>): String {
		require(sentences.isNotEmpty()) { "no timed sentences" }
		val numbered = sentences.joinToString("\n") { "[${it.index}] ${it.text}" }
		return "你是會議章節規劃助手。請把下面的逐字稿依主題分成 3 到 $MAX_CHAPTERS 個章節。" +
			"只能使用每行前面的句子編號，不要輸出或推算任何時間；不要捏造逐字稿沒有的內容。" +
			"每個章節標題最多 $MAX_TITLE_CHARS 個字、繁體中文、不要編號。" +
			"只輸出 JSON 陣列，格式：[{\"title\":\"...\",\"start_sentence_index\":0},…]，" +
			"索引必須嚴格遞增且在提供的編號範圍內。不要輸出任何其他文字。\n\n逐字稿（每行 \"[編號] 內容\"）：\n$numbered"
	}

	/**
	 * Parses and validates the model answer against [sentences].
	 * Returns null whenever anything is off (unparsable, empty, out of range, not strictly increasing, duplicate
	 * indices), so a broken answer can never be stored or shown as chapters.
	 */
	fun parse(answer: String, sentences: List<ChapterSentence>): List<ChapterDraft>? {
		if (sentences.isEmpty()) return null
		val array = extractArray(answer) ?: return null
		val drafts = mutableListOf<ChapterDraft>()
		var lastIndex = -1
		for (element in array) {
			val obj = (element as? JsonObject) ?: return null
			val title = obj["title"]?.jsonPrimitive?.contentOrNullSafe()?.trim() ?: return null
			val index = obj["start_sentence_index"]?.jsonPrimitive?.intOrNullSafe() ?: return null
			if (title.isEmpty()) return null
			if (index <= lastIndex) return null
			val sentence = sentences.firstOrNull { it.index == index } ?: return null
			lastIndex = index
			drafts += ChapterDraft(
				title = title.take(MAX_TITLE_CHARS),
				startSentenceIndex = index,
				startMs = sentence.startMs,
			)
			if (drafts.size > MAX_CHAPTERS) return null
		}
		return drafts.ifEmpty { null }
	}

	/** Accepts a bare array or one wrapped in ```json fences / prose, but never invents structure. */
	private fun extractArray(answer: String): JsonArray? {
		val start = answer.indexOf('[')
		val end = answer.lastIndexOf(']')
		if (start < 0 || end <= start) return null
		return runCatching { Json.parseToJsonElement(answer.substring(start, end + 1)).jsonArray }.getOrNull()
	}

	private fun JsonPrimitive.contentOrNullSafe(): String? =
		runCatching { if (isString) content else null }.getOrNull()

	private fun JsonPrimitive.intOrNullSafe(): Int? =
		runCatching { if (isString) content.trim().toIntOrNull() else intOrNull }.getOrNull()

	/**
	 * `recordingId`-independent revision key: transcript id plus its update time. Chapters generated for a different
	 * key are shown as possibly stale instead of being recomputed behind the user's back.
	 */
	fun revisionOf(transcriptId: String, updatedAtMillis: Long): String = "$transcriptId@$updatedAtMillis"

}
