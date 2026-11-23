package com.eva.cloudtranscribe.chat

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.cloudtranscribe.api.ChatMessage
import com.eva.cloudtranscribe.api.ChatRole
import com.eva.cloudtranscribe.model.PiiRedactor
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.qa.QaContext
import com.eva.cloudtranscribe.qa.QaSegment
import com.eva.cloudtranscribe.qa.RecordingQa
import com.eva.cloudtranscribe.usage.ModelPrice
import com.eva.cloudtranscribe.usage.TokenPricing
import com.eva.utils.UiStrings
import kotlin.math.ceil
import kotlinx.serialization.Serializable

@Serializable
enum class ChatMessageRole { USER, ASSISTANT }

@Serializable
data class ChatCitation(val segmentId: Int, val startMs: Long? = null)

/** One persisted chat turn for a recording. Never alters the underlying transcript or summary. */
@Serializable
data class RecordingChatMessage(
	val id: String,
	val recordingId: Long,
	val transcriptId: String,
	val role: ChatMessageRole,
	val text: String,
	val citations: List<ChatCitation> = emptyList(),
	val unverifiedCitations: Int = 0,
	val notFound: Boolean = false,
	val coverageNote: String? = null,
	val createdAtMillis: Long,
	val model: String? = null,
) {
	val hasUnverifiedCitations: Boolean get() = role == ChatMessageRole.ASSISTANT && !notFound && unverifiedCitations > 0
}

@Serializable
data class RecordingChatHistory(
	val recordingId: Long,
	val messages: List<RecordingChatMessage> = emptyList(),
)

/** Grounding context extracted from a recording's transcript and optional summary. */
data class RecordingChatContext(
	val transcriptId: String,
	val transcriptLabel: String,
	val segments: List<QaSegment>,
	val totalSegments: Int,
	val summaryText: String? = null,
	val summaryTruncated: Boolean = false,
	val singleSegmentTruncated: Boolean = false,
	val summaryModel: String? = null,
) {
	val transcriptTruncated: Boolean get() = segments.size < totalSegments || singleSegmentTruncated
	val truncated: Boolean get() = transcriptTruncated || summaryTruncated
	val hasSummary: Boolean get() = !summaryText.isNullOrBlank()

	val coverageNote: String?
		get() {
			if (!truncated) return null
			val parts = ArrayList<String>(2)
			if (segments.size < totalSegments) {
				val lastEnd = segments.lastOrNull()?.let { it.endMs ?: it.startMs }
				val note = if (lastEnd != null) {
					UiStrings.get(
						StringsRes.string.data_cloudtranscribe_only_was_used_segments_1_of,
						"只用了前 %1\$s（第 1–%2\$s 段，共 %3\$s 段）",
						RecordingQa.clock(lastEnd),
						segments.size,
						totalSegments,
					)
				} else {
					UiStrings.get(
						StringsRes.string.data_cloudtranscribe_only_the_first_of_segments_were_used,
						"只用了前 %1\$s 段（共 %2\$s 段）",
						segments.size,
						totalSegments,
					)
				}
				parts += note
			} else if (singleSegmentTruncated) {
				parts += UiStrings.get(
					StringsRes.string.data_cloudtranscribe_chat_transcript_truncated,
					"逐字稿過長，已截斷部分內容",
				)
			}
			if (summaryTruncated) {
				parts += UiStrings.get(
					StringsRes.string.data_cloudtranscribe_chat_summary_truncated,
					"摘要已截斷",
				)
			}
			return parts.joinToString("；")
		}
}

data class ChatParsed(
	val answer: String,
	val citations: List<ChatCitation>,
	val unverified: Int,
	val notFound: Boolean,
)

data class ChatEstimate(
	val promptTokens: Long,
	val expectedOutputTokens: Long,
	val model: String,
	val estimatedCostUsd: Double?,
	val unitPriceConfigured: Boolean,
)

/**
 * Grounded multi-turn chat over one recording's transcript and optional summary.
 * Pure rules only; never performs network calls or invents timestamps/speakers.
 */
object RecordingChat {

	const val NOT_FOUND = "錄音內容中找不到"
	const val MAX_TRANSCRIPT_CHARS = 24_000
	const val MAX_SUMMARY_CHARS = 4_000
	const val MAX_HISTORY_TURNS = 12
	const val MAX_HISTORY_CHARS = 6_000
	const val MAX_QUESTION_CHARS = 500
	const val DEFAULT_EXPECTED_OUTPUT_TOKENS = 250L

	val SUGGESTED_QUESTIONS: List<String> = listOf(
		"重點是什麼？",
		"有哪些待辦？",
		"誰說了什麼決定？",
	)

	fun usableSummary(summary: SummaryRecord?): SummaryRecord? = summary?.takeIf {
		it.status == RunStatus.SUCCESS &&
			it.text.isNotBlank() &&
			it.text.trim() != "目前無重點"
	}

	fun buildContext(
		transcript: TranscriptRecord,
		summary: SummaryRecord? = null,
		redactPii: Boolean = false,
		maxTranscriptChars: Int = MAX_TRANSCRIPT_CHARS,
		maxSummaryChars: Int = MAX_SUMMARY_CHARS,
	): RecordingChatContext {
		val rawSegments = RecordingQa.segments(PlaybackTranscripts.lines(transcript)).map { seg ->
			if (redactPii) seg.copy(text = PiiRedactor.redact(seg.text)) else seg
		}
		var used = 0
		var singleTruncated = false
		val kept = ArrayList<QaSegment>()
		for (seg in rawSegments) {
			val lineLen = numbered(seg).length + 1
			if (kept.isEmpty() && lineLen > maxTranscriptChars) {
				val budget = (maxTranscriptChars - 32).coerceAtLeast(1)
				kept += seg.copy(text = seg.text.take(budget) + "…")
				singleTruncated = true
				break
			}
			if (used + lineLen > maxTranscriptChars) break
			used += lineLen
			kept += seg
		}

		val validSummary = usableSummary(summary)
		val rawSummaryText = validSummary?.text?.trim()?.let { if (redactPii) PiiRedactor.redact(it) else it }
		var summaryTruncated = false
		val boundedSummary = when {
			rawSummaryText.isNullOrBlank() -> null
			rawSummaryText.length > maxSummaryChars -> {
				summaryTruncated = true
				rawSummaryText.take(maxSummaryChars.coerceAtLeast(1)) + "…（已截斷）"
			}
			else -> rawSummaryText
		}

		return RecordingChatContext(
			transcriptId = transcript.id,
			transcriptLabel = PlaybackTranscripts.label(transcript),
			segments = kept,
			totalSegments = rawSegments.size,
			summaryText = boundedSummary,
			summaryTruncated = summaryTruncated,
			singleSegmentTruncated = singleTruncated,
			summaryModel = validSummary?.provenance?.model,
		)
	}

	fun numbered(s: QaSegment): String {
		val time = if (s.startMs != null && s.endMs != null) " ${RecordingQa.clock(s.startMs)}–${RecordingQa.clock(s.endMs)}" else ""
		return "[S${s.id}$time] ${s.text}"
	}

	fun systemPrompt(context: RecordingChatContext): String {
		require(context.segments.isNotEmpty()) { "no segments" }
		val note = context.coverageNote?.let { "（注意：提供的錄音內容只是完整內容的一部分：$it。）\n" }.orEmpty()
		return buildString {
			appendLine("你是這段錄音的專屬問答助手。只能根據下方提供的「錄音摘要／重點」（若有）與「逐字稿」回答問題，不可使用外部知識、不可推測或編造。")
			appendLine("絕對不要捏造時間戳記或說話者；若逐字稿中未標示說話者，不要自行加上人名或 Speaker。")
			appendLine("每一句依據逐字稿的回答後面，請用〔S編號〕標註來源片段，例如〔S3〕或〔S3,S5〕；只能引用下方出現過的編號，不要自行輸出時間。")
			appendLine("如果錄音內容裡找不到答案，只回答「$NOT_FOUND」。請用繁體中文、自然簡潔的對話口吻回答。")
			if (note.isNotEmpty()) append(note)
			context.summaryText?.let { summary ->
				appendLine()
				appendLine("【錄音摘要／重點】")
				appendLine(summary)
			}
			appendLine()
			appendLine("【逐字稿（每行 \"[S編號 時間] 內容\"）】")
			append(context.segments.joinToString("\n", transform = ::numbered))
		}
	}

	fun buildMessages(
		history: List<RecordingChatMessage>,
		newQuestion: String,
		maxHistoryTurns: Int = MAX_HISTORY_TURNS,
		maxHistoryChars: Int = MAX_HISTORY_CHARS,
	): List<ChatMessage> {
		val cleanQuestion = newQuestion.trim().take(MAX_QUESTION_CHARS)
		require(cleanQuestion.isNotEmpty()) { "blank question" }

		val selected = ArrayList<ChatMessage>()
		var usedChars = 0
		for (msg in history.asReversed()) {
			if (selected.size >= maxHistoryTurns) break
			val text = msg.text.trim()
			if (text.isEmpty()) continue
			if (usedChars + text.length > maxHistoryChars) break
			usedChars += text.length
			val role = when (msg.role) {
				ChatMessageRole.USER -> ChatRole.USER
				ChatMessageRole.ASSISTANT -> ChatRole.ASSISTANT
			}
			selected += ChatMessage(role = role, content = text)
		}
		selected.reverse()
		selected += ChatMessage(role = ChatRole.USER, content = cleanQuestion)
		return selected
	}

	fun parse(answer: String, context: RecordingChatContext): ChatParsed {
		val trimmed = answer.trim()
		val qaParsed = RecordingQa.parse(trimmed, QaContext(context.segments, context.totalSegments))
		val unquoted = trimmed.removePrefix("「")
		val notFound = qaParsed.notFound ||
			trimmed.startsWith(NOT_FOUND) ||
			unquoted.startsWith(NOT_FOUND)
		return ChatParsed(
			answer = qaParsed.answer,
			citations = qaParsed.citations.map { ChatCitation(it.segmentId, it.startMs) },
			unverified = qaParsed.unverified,
			notFound = notFound,
		)
	}

	/**
	 * Conservative client-side token estimate (CJK chars ≈ 1.5 tokens, ASCII/other ≈ 0.25 tokens).
	 * Used only to display pre-send cost estimates before any paid request is sent.
	 */
	fun estimateTokens(text: String): Long {
		if (text.isBlank()) return 0L
		var cjk = 0L
		var other = 0L
		var i = 0
		while (i < text.length) {
			val cp = text.codePointAt(i)
			i += Character.charCount(cp)
			if (cp >= 0x2E80) cjk++ else other++
		}
		return ceil(cjk * 1.5 + other * 0.25).toLong().coerceAtLeast(1L)
	}

	fun estimateRequest(
		context: RecordingChatContext,
		history: List<RecordingChatMessage>,
		draftQuestion: String,
		model: String,
		prices: List<ModelPrice>,
		expectedOutputTokens: Long = DEFAULT_EXPECTED_OUTPUT_TOKENS,
	): ChatEstimate {
		val sysTokens = estimateTokens(systemPrompt(context))
		val q = draftQuestion.trim().ifEmpty { SUGGESTED_QUESTIONS.first() }
		val msgs = buildMessages(history, q)
		val msgTokens = msgs.sumOf { estimateTokens(it.content) + 8L }
		val promptTokens = sysTokens + msgTokens + 12L

		val price = TokenPricing.priceMap(prices)[model.trim().lowercase()]
		val inputPerM = TokenPricing.parseUnitPrice(price?.inputPerMillion)
		val outputPerM = TokenPricing.parseUnitPrice(price?.outputPerMillion)
		val cost = if (inputPerM != null) {
			val inputCost = promptTokens / TokenPricing.PER_MILLION * inputPerM
			val outputCost = if (outputPerM != null) expectedOutputTokens / TokenPricing.PER_MILLION * outputPerM else 0.0
			inputCost + outputCost
		} else null

		return ChatEstimate(
			promptTokens = promptTokens,
			expectedOutputTokens = expectedOutputTokens,
			model = model,
			estimatedCostUsd = cost,
			unitPriceConfigured = inputPerM != null,
		)
	}
}
