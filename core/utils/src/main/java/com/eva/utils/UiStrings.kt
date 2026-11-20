package com.eva.utils

import java.util.Locale

/** Resolves localized status messages from code that also runs in plain JVM tests. */
object UiStrings {
	@Volatile
	var resolve: ((Int, Array<out Any?>) -> String)? = null

	fun get(id: Int, fallback: String, vararg args: Any?): String =
		resolve?.invoke(id, args) ?: if (args.isEmpty()) fallback else String.format(Locale.ROOT, fallback, *args)
}
