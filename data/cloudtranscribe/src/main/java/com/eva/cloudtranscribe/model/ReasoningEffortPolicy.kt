package com.eva.cloudtranscribe.model

import java.util.Locale

/**
 * Single source for when `reasoning_effort` is actually sent.
 * Mirrors [com.eva.cloudtranscribe.api.OpenAiCompatibleClient.shouldSendEffort] and
 * [CloudSettings.transcriptionEffort]; Gemini native never sends the field.
 */
enum class ReasoningEffortSkip {
	OFF,
	DEFAULT_TRANSCRIBE_MODEL,
	SPEAKER_DIARIZATION,
	MODEL_NAME_TRANSCRIBE,
	MODEL_NAME_FLASH_LITE,
	GEMINI_NATIVE,
}

data class ReasoningEffortApplicability(
	val summaryModel: String,
	val transcribeModel: String,
	val summarySkip: ReasoningEffortSkip?,
	val transcribeSkip: ReasoningEffortSkip?,
) {
	val summarySent: Boolean get() = summarySkip == null
	val transcribeSent: Boolean get() = transcribeSkip == null
}

object ReasoningEffortPolicy {
	fun shouldSend(model: String, effort: ReasoningEffort): Boolean =
		effort.wire != null &&
			!model.contains("transcribe", ignoreCase = true) &&
			!model.contains("flash-lite", ignoreCase = true)

	fun of(settings: CloudSettings): ReasoningEffortApplicability =
		ReasoningEffortApplicability(
			summaryModel = settings.summaryModel,
			transcribeModel = settings.transcribeModel,
			summarySkip = summarySkip(settings),
			transcribeSkip = transcribeSkip(settings),
		)

	fun formatOneLiner(settings: CloudSettings, copy: Copy): String = formatOneLiner(of(settings), copy)

	fun formatOneLiner(note: ReasoningEffortApplicability, copy: Copy): String {
		val left = if (note.summarySkip == null) {
			copy.appliesToSummary.format(Locale.ROOT, note.summaryModel)
		} else {
			copy.appliesToSummarySkipped.format(Locale.ROOT, note.summaryModel, copy.reason(note.summarySkip))
		}
		val right = if (note.transcribeSkip == null) {
			copy.transcribeSent.format(Locale.ROOT, note.transcribeModel)
		} else {
			copy.transcribeSkipped.format(Locale.ROOT, note.transcribeModel, copy.reason(note.transcribeSkip))
		}
		return copy.join.format(Locale.ROOT, left, right)
	}

	private fun summarySkip(settings: CloudSettings): ReasoningEffortSkip? {
		if (settings.providerType == ProviderType.GEMINI_NATIVE) return ReasoningEffortSkip.GEMINI_NATIVE
		if (settings.reasoningEffort.wire == null) return ReasoningEffortSkip.OFF
		return modelNameSkip(settings.summaryModel)
	}

	private fun transcribeSkip(settings: CloudSettings): ReasoningEffortSkip? {
		if (settings.providerType == ProviderType.GEMINI_NATIVE) return ReasoningEffortSkip.GEMINI_NATIVE
		if (settings.reasoningEffort.wire == null) return ReasoningEffortSkip.OFF
		if (settings.speakerDiarization) return ReasoningEffortSkip.SPEAKER_DIARIZATION
		if (settings.transcribeModel == CloudSettings.DEFAULT_MODEL) return ReasoningEffortSkip.DEFAULT_TRANSCRIBE_MODEL
		return modelNameSkip(settings.transcribeModel)
	}

	private fun modelNameSkip(model: String): ReasoningEffortSkip? = when {
		model.contains("transcribe", ignoreCase = true) -> ReasoningEffortSkip.MODEL_NAME_TRANSCRIBE
		model.contains("flash-lite", ignoreCase = true) -> ReasoningEffortSkip.MODEL_NAME_FLASH_LITE
		else -> null
	}

	data class Copy(
		val appliesToSummary: String,
		val appliesToSummarySkipped: String,
		val transcribeSent: String,
		val transcribeSkipped: String,
		val join: String,
		val skipOff: String,
		val skipDefaultModel: String,
		val skipDiarization: String,
		val skipTranscribeName: String,
		val skipFlashLite: String,
		val skipGeminiNative: String,
	) {
		fun reason(skip: ReasoningEffortSkip): String = when (skip) {
			ReasoningEffortSkip.OFF -> skipOff
			ReasoningEffortSkip.DEFAULT_TRANSCRIBE_MODEL -> skipDefaultModel
			ReasoningEffortSkip.SPEAKER_DIARIZATION -> skipDiarization
			ReasoningEffortSkip.MODEL_NAME_TRANSCRIBE -> skipTranscribeName
			ReasoningEffortSkip.MODEL_NAME_FLASH_LITE -> skipFlashLite
			ReasoningEffortSkip.GEMINI_NATIVE -> skipGeminiNative
		}
	}
}
