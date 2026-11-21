package com.eva.transcribe.domain.models

import com.eva.transcribe.R as StringsRes
import com.eva.utils.UiStrings

/** Archive basename is the publisher's exact checkpoint; per-file repositories use the pinned catalogue name. */
fun STTModel.checkpointIdentifier(): String = when (archiveFormat) {
    "zip" -> downloadURI.substringBefore('?').substringAfterLast('/').removeSuffix(".zip")
    "tar.bz2" -> downloadURI.substringBefore('?').substringAfterLast('/').removeSuffix(".tar.bz2")
    else -> name
}.ifBlank { name }

fun STTModel.recognitionMode(): String = if (engine == "vosk") UiStrings.get(StringsRes.string.data_transcribe_vosk_on_device_streaming_recognition, "Vosk · 手機離線串流辨識") else UiStrings.get(StringsRes.string.data_transcribe_sherpa_onnx_on_device_recognition_after_pauses, "sherpa-onnx · 手機離線分句辨識（停頓後出字）")

/** Looks up the localized catalogue label without changing persisted model metadata. */
fun STTModel.localizedName(): String = when (modelId) {
	"vosk-en-us-small" -> UiStrings.get(StringsRes.string.stt_label_vosk_en_us_small, displayName)
	"vosk-cn-small" -> UiStrings.get(StringsRes.string.stt_label_vosk_cn_small, displayName)
	"vosk-hi-small" -> UiStrings.get(StringsRes.string.stt_label_vosk_hi_small, displayName)
	"vosk-tr-small" -> UiStrings.get(StringsRes.string.stt_label_vosk_tr_small, displayName)
	"vosk-es-small" -> UiStrings.get(StringsRes.string.stt_label_vosk_es_small, displayName)
	"vosk-de-small" -> UiStrings.get(StringsRes.string.stt_label_vosk_de_small, displayName)
	"sherpa-sensevoice-small-int8" -> UiStrings.get(StringsRes.string.stt_label_sherpa_sensevoice_small_int8, displayName)
	"sherpa-qwen3-asr-0.6b-int8" -> UiStrings.get(StringsRes.string.stt_label_sherpa_qwen3_asr_0_6b_int8, displayName)
	"sherpa-qwen3-asr-1.7b-int8" -> UiStrings.get(StringsRes.string.stt_label_sherpa_qwen3_asr_1_7b_int8, displayName)
	"sherpa-firered-asr2-aed-int8" -> UiStrings.get(StringsRes.string.stt_label_sherpa_firered_asr2_aed_int8, displayName)
	else -> displayName
}
