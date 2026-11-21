package com.eva.cloudtranscribe.usage

import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

object DiskUsage {
	fun directoryBytes(dir: File): Long {
		if (!dir.exists()) return 0L
		var sum = 0L
		dir.walkTopDown().forEach { file ->
			if (file.isFile) sum += file.length().coerceAtLeast(0L)
		}
		return sum
	}

	fun totalBytes(parts: Iterable<Long>): Long = parts.fold(0L) { acc, n -> acc + n.coerceAtLeast(0L) }

	fun formatBytes(bytes: Long): String {
		if (bytes <= 0L) return "0 MB"
		val gb = 1024.0 * 1024.0 * 1024.0
		val mb = 1024.0 * 1024.0
		val kb = 1024.0
		return when {
			bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
			bytes >= mb -> String.format(Locale.US, "%d MB", (bytes / mb).roundToInt().coerceAtLeast(1))
			else -> String.format(Locale.US, "%d KB", (bytes / kb).roundToInt().coerceAtLeast(1))
		}
	}
}
