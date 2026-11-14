package com.eva.cloudtranscribe.followup

import com.eva.cloudtranscribe.live.LiveCloudSettings

/** One to-do line already stored for the recording (text exactly as the user / earlier flow wrote it). */
data class FollowUpItem(val text: String, val isDone: Boolean)

/** The only text a follow-up draft may be built from. Audio and the transcript are never part of it. */
data class FollowUpMaterials(
	val summary: String?,
	val actionItems: List<FollowUpItem>,
	/** True when the materials were cut to [FollowUpPlanner.MAX_MATERIAL_CHARS]. */
	val truncated: Boolean = false,
)

/**
 * Pure rules for the post-meeting follow-up draft: which material is usable, the prompt, and what output is accepted.
 * Nothing here sends anything; the draft is only ever copied or shared by the user, never sent automatically.
 */
object FollowUpPlanner {

	const val MAX_MATERIAL_CHARS = 6_000
	const val MAX_INSTRUCTIONS_CHARS = 1_000

	private val PLACEHOLDERS = setOf(
		LiveCloudSettings.NO_KEY_POINTS,
		"${LiveCloudSettings.NO_KEY_POINTS}。",
		"- ${LiveCloudSettings.NO_KEY_POINTS}",
		"- ${LiveCloudSettings.NO_KEY_POINTS}。",
		"（尚無）",
	)

	/** True when [text] has real content (not blank and not only the "no key points" placeholder). */
	fun hasContent(text: String?): Boolean {
		if (text.isNullOrBlank()) return false
		val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
		return lines.any { it !in PLACEHOLDERS }
	}

	/**
	 * Builds the materials, or null when there is nothing accepted to write from (then no request may be made).
	 * Only a successful, non-placeholder summary and already stored to-dos count; a live draft never does.
	 */
	fun materials(acceptedSummary: String?, items: List<FollowUpItem>): FollowUpMaterials? {
		val summary = acceptedSummary?.trim()?.takeIf { hasContent(it) }
		val usable = items.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
		if (summary == null && usable.isEmpty()) return null
		var budget = MAX_MATERIAL_CHARS
		var truncated = false
		val cutSummary = summary?.let {
			if (it.length > budget) { truncated = true; it.take(budget) } else it
		}
		budget -= cutSummary?.length ?: 0
		val keptItems = buildList {
			for (item in usable) {
				if (item.text.length + 3 > budget) { truncated = true; break }
				add(item)
				budget -= item.text.length + 3
			}
		}
		return FollowUpMaterials(cutSummary, keptItems, truncated)
	}

	/** Prompt sent to the user's configured summary model. [customInstructions] replaces the default style part. */
	fun prompt(materials: FollowUpMaterials, customInstructions: String = ""): String = buildString {
		appendLine("請根據下面「會議資料」，寫一則會後要傳給與會者或同事的跟催訊息草稿。")
		appendLine()
		appendLine("必須遵守：")
		appendLine("- 只能使用會議資料裡明確寫出的事實。不得發明負責人、期限、日期、數字、發言者或未出現的決定；資料沒寫就省略。")
		appendLine("- 不要捏造時間戳或引用。")
		appendLine("- 沒有待辦就省略待辦段落；不要寫「目前無重點」這類空話。")
		appendLine("- 只輸出訊息本文，不要加說明、標題或程式碼區塊。")
		appendLine("- 預設用繁體中文；若會議資料明顯是其他語言，改用該語言。")
		appendLine()
		val style = customInstructions.trim().take(MAX_INSTRUCTIONS_CHARS)
		if (style.isNotEmpty()) {
			appendLine("使用者的格式要求（不得違反上面的規則）：")
			appendLine(style)
		} else {
			appendLine("格式：一句問候或說明場合，接著幾點結論，再列待辦（若有），最後一句簡短收尾。語氣中性、簡潔，整體不超過 200 字。")
		}
		appendLine()
		appendLine("會議資料：")
		materials.summary?.let {
			appendLine("【摘要】")
			appendLine(it)
		}
		if (materials.actionItems.isNotEmpty()) {
			appendLine("【待辦】")
			materials.actionItems.forEach { item ->
				appendLine("- ${if (item.isDone) "（已完成）" else ""}${item.text}")
			}
		}
	}.trimEnd()

	/** Cleans a model reply; null when it is empty or only placeholder text (then the old draft is kept). */
	fun accept(output: String?): String? {
		if (output == null) return null
		var text = output.trim()
		if (text.startsWith("```")) {
			text = text.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```").trim()
		}
		return text.takeIf { hasContent(it) }
	}

	/** Identity of the material the draft was built from, so the UI can say "摘要已更新". */
	fun revisionOf(summaryId: String?, materials: FollowUpMaterials?): String {
		if (materials == null) return ""
		val basis = buildString {
			append(summaryId.orEmpty()).append('|')
			append(materials.summary.orEmpty()).append('|')
			materials.actionItems.forEach { append(it.isDone).append(':').append(it.text).append('\n') }
		}
		return "%08x".format(basis.hashCode())
	}
}
