package com.eva.utils

/**
 * Key used when two user-visible lines must be treated as the same entry (e.g. a to-do that is added twice):
 * surrounding whitespace is dropped, inner whitespace is collapsed, and comparison ignores case.
 */
fun normalizeForComparison(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase()
