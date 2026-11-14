package com.eva.cloudtranscribe.outline

import com.eva.cloudtranscribe.live.LiveCloudSettings
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/** One outline node. At most two levels: a root title with leaf children. */
@Serializable
data class OutlineNode(val title: String, val children: List<OutlineNode> = emptyList())

/** AI-organised outline saved for one recording; [summaryHash] tells whether the summary changed since. */
@Serializable
data class SavedOutline(
	val summaryId: String,
	val summaryHash: String,
	val nodes: List<OutlineNode>,
	val createdAtMillis: Long,
	val model: String? = null,
)

/**
 * Outline view of a saved summary.
 * - [local]: free, on-device, derived every time from the summary text (no storage, no network).
 * - [prompt] / [parseAi]: optional, manual "整理成大綱" that sends only the summary text; the answer is validated
 *   strictly (≤ 2 levels, ≤ [MAX_NODES] nodes, non-empty titles, no other fields) or rejected as a whole.
 */
object SummaryOutline {

	const val DEFAULT_ROOT = "重點"
	const val MAX_NODES = 20
	const val MAX_TITLE_CHARS = 80

	private val BULLET = Regex("^(?:[-•*・]|\\d+[)、]|\\d+\\.(?!\\d))\\s*")
	private val HEADING = Regex("^#{1,6}\\s+")
	private val BOLD_LINE = Regex("^\\*\\*(.+?)\\*\\*[：:]?$")
	private val TOPIC = Regex("^(?:主題|議題|Topic)\\s*[：:]\\s*(.+)$", RegexOption.IGNORE_CASE)

	fun isEmptySummary(summary: String?): Boolean {
		val text = summary?.trim().orEmpty()
		return text.isEmpty() || text.trimEnd('。', '.') == LiveCloudSettings.NO_KEY_POINTS
	}

	/** Bullets become leaves; `## heading`, `**heading**`, `主題：…` or a non-bullet line ending in `：` become parents. */
	fun local(summary: String?): List<OutlineNode> {
		if (isEmptySummary(summary)) return emptyList()
		val roots = mutableListOf<Pair<String, MutableList<OutlineNode>>>()
		var loose: MutableList<OutlineNode>? = null
		fun leaf(text: String) {
			val target = roots.lastOrNull()?.second ?: (loose ?: mutableListOf<OutlineNode>().also { loose = it })
			target += OutlineNode(text.take(MAX_TITLE_CHARS))
		}
		for (raw in summary!!.lines()) {
			val line = raw.trim()
			if (line.isEmpty()) continue
			val heading = parentTitle(line)
			when {
				heading != null -> roots += heading.take(MAX_TITLE_CHARS) to mutableListOf()
				BULLET.containsMatchIn(line) -> line.replace(BULLET, "").trim().takeIf { it.isNotEmpty() && !isEmptySummary(it) }?.let(::leaf)
				else -> if (!isEmptySummary(line)) leaf(line)
			}
		}
		val result = mutableListOf<OutlineNode>()
		loose?.let { result += OutlineNode(DEFAULT_ROOT, it) }
		roots.forEach { (title, children) -> result += OutlineNode(title, children) }
		return result
	}

	private fun parentTitle(line: String): String? {
		HEADING.find(line)?.let { return line.removeRange(it.range).trim().trim('*').trim().ifEmpty { null } }
		BOLD_LINE.matchEntire(line)?.let { return it.groupValues[1].trim().ifEmpty { null } }
		TOPIC.matchEntire(line)?.let { return it.groupValues[1].trim().ifEmpty { null } }
		if (!BULLET.containsMatchIn(line) && (line.endsWith("：") || line.endsWith(":")) && line.length <= 40)
			return line.dropLast(1).trim().ifEmpty { null }
		return null
	}

	fun prompt(summary: String): String {
		require(!isEmptySummary(summary)) { "empty summary" }
		return "請把下面這份會議摘要整理成階層大綱，只能使用摘要裡已有的內容，不要新增、推測或補充資訊。" +
			"最多兩層：主題，以及每個主題底下的重點；總節點數（含主題）最多 $MAX_NODES 個。" +
			"每個標題用繁體中文、最多 $MAX_TITLE_CHARS 字。" +
			"只輸出 JSON 陣列，格式：[{\"title\":\"主題\",\"children\":[{\"title\":\"重點\"}]}]，不要輸出任何其他文字。" +
			"\n\n摘要：\n${summary.trim()}"
	}

	/** Validated outline, or null when the answer breaks any rule (so a bad answer never replaces the local outline). */
	fun parseAi(answer: String): List<OutlineNode>? {
		val start = answer.indexOf('[')
		val end = answer.lastIndexOf(']')
		if (start < 0 || end <= start) return null
		val array = runCatching { Json.parseToJsonElement(answer.substring(start, end + 1)).jsonArray }.getOrNull() ?: return null
		var count = 0
		fun node(element: Any?, depth: Int): OutlineNode? {
			val obj = element as? JsonObject ?: return null
			if (obj.keys.any { it != "title" && it != "children" }) return null
			val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.replace(Regex("\\s+"), " ")
			if (title.isNullOrEmpty()) return null
			count++
			val childrenElement = obj["children"]
			val children = when (childrenElement) {
				null -> emptyList()
				is JsonArray -> {
					if (childrenElement.isNotEmpty() && depth >= 2) return null
					childrenElement.map { node(it, depth + 1) ?: return null }
				}
				else -> return null
			}
			return OutlineNode(title.take(MAX_TITLE_CHARS), children)
		}
		val nodes = array.map { node(it, 1) ?: return null }
		if (nodes.isEmpty() || count > MAX_NODES) return null
		return nodes
	}

	fun hash(summary: String): String =
		MessageDigest.getInstance("SHA-256").digest(summary.trim().toByteArray(Charsets.UTF_8))
			.take(12).joinToString("") { "%02x".format(it) }

	/** All titles flattened in display order (used for copy and tests). */
	fun flatten(nodes: List<OutlineNode>): List<Pair<Int, String>> =
		nodes.flatMap { listOf(0 to it.title) + it.children.map { c -> 1 to c.title } }

	fun asText(nodes: List<OutlineNode>): String =
		flatten(nodes).joinToString("\n") { (level, title) -> if (level == 0) title else "  - $title" }
}
