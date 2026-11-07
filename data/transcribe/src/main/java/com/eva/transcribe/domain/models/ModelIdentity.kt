package com.eva.transcribe.domain.models

/** Archive basename is the publisher's exact checkpoint; per-file repositories use the pinned catalogue name. */
fun STTModel.checkpointIdentifier(): String = when (archiveFormat) {
    "zip" -> downloadURI.substringBefore('?').substringAfterLast('/').removeSuffix(".zip")
    "tar.bz2" -> downloadURI.substringBefore('?').substringAfterLast('/').removeSuffix(".tar.bz2")
    else -> name
}.ifBlank { name }

fun STTModel.recognitionMode(): String = if (engine == "vosk") "Vosk · 手機離線串流辨識" else "sherpa-onnx · 手機離線分句辨識（停頓後出字）"
