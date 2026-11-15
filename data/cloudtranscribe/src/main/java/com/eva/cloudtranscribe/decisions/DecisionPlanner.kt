package com.eva.cloudtranscribe.decisions

import com.eva.cloudtranscribe.model.PlaybackLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

/** One transcript sentence the model may reference by [index]; [startMs] is null when the transcript has no real timing. */
data class DecisionSentence(val index: Int, val startMs: Long?, val text: String)

/** A decision before it is stored; [startMs] was resolved locally from the sentence index (never from the model). */
data class DecisionDraft(val text: String, val sourceSentenceIndex: Int?, val startMs: Long?)

/**
 * Builds the "extract decisions" request and validates the answer. The model may only return
 * `[{text, start_sentence_index?}]`; any other field (times, speakers, owners, ...) or any broken item rejects the whole
 * batch, so a malformed answer can never be stored. Nothing here talks to a network.
 */
object DecisionPlanner {

	const val MAX_DECISIONS = 20
	const val MAX_TEXT_CHARS = 200
	private val ALLOWED_KEYS = setOf("text", "start_sentence_index")

	fun sentences(lines: List<PlaybackLine>): List<DecisionSentence> =
		lines.mapIndexedNotNull { index, line ->
			DecisionSentence(index, line.startMs, line.text.trim()).takeIf { it.text.isNotEmpty() }
		}

	fun prompt(sentences: List<DecisionSentence>): String {
		require(sentences.isNotEmpty()) { "no sentences" }
		val numbered = sentences.joinToString("\n") { "[${it.index}] ${it.text}" }
		return "你是會議記錄助手。請只列出下面逐字稿裡「明確說出」的決定、共識或承諾（例如「我們決定…」「大家同意…」「就這樣定了」）。" +
			"不要列待辦事項、猜測、建議、希望或還在討論中的內容；不要推測負責人；不要捏造逐字稿沒有的內容。" +
			"每項用繁體中文一句話、最多 $MAX_TEXT_CHARS 字。可以附上該決定最早出現的句子編號。" +
			"不要輸出任何時間、時間戳或說話者姓名。沒有明確決定時回傳 []。" +
			"只輸出 JSON 陣列，格式：[{\"text\":\"...\",\"start_sentence_index\":0}]，最多 $MAX_DECISIONS 項，不要輸出任何其他文字。" +
			"\n\n逐字稿（每行 \"[編號] 內容\"）：\n$numbered"
	}

	/**
	 * Returns the validated decisions (possibly empty when the model found none), or null when anything is off:
	 * unparsable, non-object items, missing / blank text, unknown fields (e.g. a fabricated time or speaker), an index
	 * that is not one of the provided sentences, or too many items.
	 */
	fun parse(answer: String, sentences: List<DecisionSentence>): List<DecisionDraft>? {
		if (sentences.isEmpty()) return null
		val array = extractArray(answer) ?: return null
		if (array.size > MAX_DECISIONS) return null
		val byIndex = sentences.associateBy { it.index }
		val seen = HashSet<String>()
		val drafts = mutableListOf<DecisionDraft>()
		for (element in array) {
			val obj = element as? JsonObject ?: return null
			if (obj.keys.any { it !in ALLOWED_KEYS }) return null
			val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.replace(Regex("\\s+"), " ")
			if (text.isNullOrEmpty()) return null
			val rawIndex = obj["start_sentence_index"]
			val sentence = when {
				rawIndex == null || rawIndex is JsonNull -> null
				else -> {
					val index = (rawIndex as? JsonPrimitive)?.let { p -> if (p.isString) p.content.trim().toIntOrNull() else p.intOrNull }
						?: return null
					byIndex[index] ?: return null
				}
			}
			if (!seen.add(text.lowercase())) continue
			drafts += DecisionDraft(text.take(MAX_TEXT_CHARS), sentence?.index, sentence?.startMs)
		}
		return drafts
	}

	private fun extractArray(answer: String): JsonArray? {
		val start = answer.indexOf('[')
		val end = answer.lastIndexOf(']')
		if (start < 0 || end <= start) return null
		return runCatching { Json.parseToJsonElement(answer.substring(start, end + 1)).jsonArray }.getOrNull()
	}

	fun revisionOf(transcriptId: String, updatedAtMillis: Long): String = "$transcriptId@$updatedAtMillis"
}
