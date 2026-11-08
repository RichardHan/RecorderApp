package com.eva.transcribe.data

/** Manifest paths for per-file models: relative, forward-slash, no traversal, no hidden/absolute parts. */
object PerFileModelPaths {
	private val SEGMENT = Regex("^[A-Za-z0-9_][A-Za-z0-9_.\\-]*$")
	fun isSafe(path: String): Boolean =
		path.isNotBlank() && !path.startsWith("/") && !path.contains('\\') &&
			path.split('/').all { it != ".." && it != "." && SEGMENT.matches(it) } && !path.endsWith(".part")
}
