package com.eva.cloudtranscribe.translate

import com.eva.cloudtranscribe.model.PlaybackLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray

enum class TranslationLanguage(val tag: String, val label: String, val promptName: String) {
	ZH_HANT("zh-Hant", "繁體中文", "繁體中文（台灣用語）"),
	EN("en", "English", "English"),
	JA("ja", "日本語", "日本語"),
}

enum class TranslationView { ORIGINAL, TRANSLATION, BILINGUAL }

/** One numbered transcript line; [id] is `line index + 1` of [com.eva.cloudtranscribe.model.PlaybackTranscripts.lines]. */
data class TranslationSentence(val id: Int, val text: String)

@Serializable
data class TranslatedLine(val id: Int, val translation: String)

/** Latest translation of one transcript into one language. Read-only: fix the original, or delete and re-translate. */
@Serializable
data class SavedTranslation(
	val recordingId: Long,
	val transcriptId: String,
	val targetLang: String,
	val sourceRevision: String,
	val lines: List<TranslatedLine>,
	val createdAtMillis: Long,
	val model: String? = null,
)

/**
 * Builds translation requests (numbered sentences, chunked) and validates answers. The model must return exactly the
 * ids it was given — a missing, extra or duplicate id, an empty translation or any other field rejects the whole chunk,
 * so it can never invent or drop sentences. Nothing here talks to a network.
 */
object TranscriptTranslation {

	const val MAX_CHUNK_CHARS = 6_000

	fun sentences(lines: List<PlaybackLine>): List<TranslationSentence> =
		lines.mapIndexed { i, line -> TranslationSentence(i + 1, line.text.trim()) }.filter { it.text.isNotEmpty() }

	fun chunks(sentences: List<TranslationSentence>, maxChars: Int = MAX_CHUNK_CHARS): List<List<TranslationSentence>> {
		val result = mutableListOf<List<TranslationSentence>>()
		var current = mutableListOf<TranslationSentence>()
		var size = 0
		for (s in sentences) {
			val cost = s.text.length + 12
			if (current.isNotEmpty() && size + cost > maxChars) {
				result += current
				current = mutableListOf()
				size = 0
			}
			current += s
			size += cost
		}
		if (current.isNotEmpty()) result += current
		return result
	}

	fun prompt(chunk: List<TranslationSentence>, language: TranslationLanguage): String {
		require(chunk.isNotEmpty()) { "empty chunk" }
		val payload = chunk.joinToString(",", "[", "]") { s ->
			JsonObject(mapOf("id" to JsonPrimitive(s.id), "text" to JsonPrimitive(s.text))).toString()
		}
		return "請把下面每一句逐字稿翻譯成${language.promptName}。逐句翻譯，保持原意，不要合併、拆分、刪除或新增句子，" +
			"不要加入原文沒有的內容或說明；專有名詞與人名可保留原文。每個 id 都必須出現一次。" +
			"只輸出 JSON 陣列，格式：[{\"id\":1,\"translation\":\"...\"}]，不要輸出任何其他文字。\n\n原文：\n$payload"
	}

	fun parse(answer: String, chunk: List<TranslationSentence>): List<TranslatedLine>? {
		val start = answer.indexOf('[')
		val end = answer.lastIndexOf(']')
		if (start < 0 || end <= start) return null
		val array = runCatching { Json.parseToJsonElement(answer.substring(start, end + 1)).jsonArray }.getOrNull() ?: return null
		val expected = chunk.map { it.id }.toSet()
		val seen = HashMap<Int, String>()
		for (element in array) {
			val obj = element as? JsonObject ?: return null
			if (obj.keys.any { it != "id" && it != "translation" }) return null
			val idPrimitive = obj["id"] as? JsonPrimitive ?: return null
			val id = (if (idPrimitive.isString) idPrimitive.content.trim().toIntOrNull() else idPrimitive.intOrNull) ?: return null
			val text = (obj["translation"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
			if (text.isNullOrEmpty()) return null
			if (id !in expected || seen.put(id, text) != null) return null
		}
		if (seen.keys != expected) return null
		return chunk.map { TranslatedLine(it.id, seen.getValue(it.id)) }
	}

	fun revisionOf(transcriptId: String, updatedAtMillis: Long): String = "$transcriptId@$updatedAtMillis"

	fun isStale(saved: SavedTranslation, transcriptId: String, updatedAtMillis: Long): Boolean =
		saved.transcriptId != transcriptId || saved.sourceRevision != revisionOf(transcriptId, updatedAtMillis)

	/** Per-line translation aligned to line indices of the transcript (null where a line has none). */
	fun aligned(lineCount: Int, saved: SavedTranslation?): List<String?>? {
		saved ?: return null
		val byId = saved.lines.associate { it.id to it.translation }
		return List(lineCount) { i -> byId[i + 1] }
	}

	/** Text a line shows for [view]; timing and seeking always stay on the original line. */
	fun display(original: String, translation: String?, view: TranslationView): Pair<String, String?> = when (view) {
		TranslationView.ORIGINAL -> original to null
		TranslationView.TRANSLATION -> (translation ?: original) to null
		TranslationView.BILINGUAL -> original to translation
	}
}
