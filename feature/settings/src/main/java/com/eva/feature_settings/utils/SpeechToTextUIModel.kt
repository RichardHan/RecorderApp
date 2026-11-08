package com.eva.feature_settings.utils

import androidx.compose.runtime.Immutable
import com.eva.transcribe.domain.models.STTModel
import kotlin.uuid.Uuid

@Immutable
internal data class SpeechToTextUIModel(
    val model: STTModel,
    val progress: () -> Float = { 0f },
    val isRunning: Boolean = false,
    val downloadId: Uuid? = null,
    /** Current worker stage (下載中／驗證檔案／解壓安裝…) so a long post-download phase is not mistaken for a hang. */
    val statusText: String = "",
)
