package com.eva.transcribe.data

/** Pure helpers for model download progress / resume decisions (unit-tested). */
object ModelDownloadProgress {
    /** 0..100, never NaN; unknown total reports 0. */
    fun percent(done: Long, total: Long): Int =
        if (total <= 0) 0 else ((done.coerceIn(0, total).toDouble() / total) * 100).toInt().coerceIn(0, 100)

    /** Append only when we asked for a range (offset > 0) and the server honoured it with 206. */
    fun resumeAppends(offset: Long, httpCode: Int): Boolean = offset > 0 && httpCode == 206
}
