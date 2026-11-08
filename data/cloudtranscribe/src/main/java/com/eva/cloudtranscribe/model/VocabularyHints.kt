package com.eva.cloudtranscribe.model

import java.util.Locale

/** Bounded spelling data, never a prompt template or replacement transcript. */
object VocabularyHints {
	const val MAX_TERMS = 50
	const val MAX_TERM_LENGTH = 80

	/** Preserve spelling/order, collapse whitespace, and remove case-insensitive duplicates. */
	fun normalize(terms: List<String>): List<String> = cleaned(terms)
		.filter { it.length <= MAX_TERM_LENGTH }
		.distinctBy { it.lowercase(Locale.ROOT) }
		.take(MAX_TERMS)
		.toList()

	/** The editor rejects excess data rather than silently truncating a name when saving. */
	fun isWithinLimits(terms: List<String>): Boolean =
		cleaned(terms).none { it.length > MAX_TERM_LENGTH } &&
			cleaned(terms).distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_TERMS + 1).count() <= MAX_TERMS

	private fun cleaned(terms: List<String>): Sequence<String> = terms.asSequence()
		.map { term -> term.map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }.joinToString("").trim().replace(SPACES, " ") }
		.filter { it.isNotEmpty() }

	private val SPACES = Regex(" +")
}
