package com.eva.transcribe.resources

/** Single admission policy shared by the downloader, recognizer and settings. All units are bytes. */
object ResourcePolicy {
    const val FILE_BUFFER = 3584L * 1024 * 1024
    const val ARCHIVE_BUFFER = 200L * 1024 * 1024
    const val LARGE_MODEL = 1_500_000_000L
    fun ramBytes(weights: Long) = weights + weights * 3 / 10 + 512L * 1024 * 1024
    fun archiveBytes(download: Long, extracted: Long?) = download + (extracted ?: download * 2) + ARCHIVE_BUFFER
    fun partialBytes(expected: Long, completed: Long?, partial: Long?): Long = when {
        completed == expected -> expected
        partial != null && partial in 0..expected -> partial
        else -> 0L
    }
    fun downloadBytes(files: Boolean, download: Long, extracted: Long?, already: Long, installed: Boolean): Long = when {
        installed -> 0
        files -> (download - already.coerceIn(0, download)) + FILE_BUFFER
        else -> archiveBytes(download, extracted)
    }
    fun diskEnough(free: Long?, needed: Long): Boolean? = free?.let { it >= needed }
    fun ramEnough(free: Long?, low: Boolean?, needed: Long?, is64: Boolean, weights: Long?): Boolean? {
        if (weights != null && weights >= LARGE_MODEL && !is64) return false
        if (needed == null || free == null || low == null) return null
        return !low && free >= needed
    }
}

data class ResourceSnapshot(
    val diskFree: Long? = null, val diskTotal: Long? = null,
    val ramFree: Long? = null, val ramTotal: Long? = null,
    val lowMemory: Boolean? = null, val threshold: Long? = null,
    val is64: Boolean = true,
    val models: Map<String, ModelResources> = emptyMap(),
)
data class ModelResources(
    val installed: Boolean, val download: Long, val already: Long,
    val diskNeeded: Long, val ramNeeded: Long?, val weights: Long?,
)

/** Only an actual system low-memory state produces text; per-model storage/RAM demand warnings live in resourceDescription. */
fun resourceWarning(s: ResourceSnapshot): String? =
    if (s.lowMemory == true) "⚠ 手機 RAM 不足（系統低記憶體）" else null
