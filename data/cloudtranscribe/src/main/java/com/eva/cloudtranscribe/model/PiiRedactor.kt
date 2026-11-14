package com.eva.cloudtranscribe.model

/**
 * Best-effort local masking of obvious personal data before transcript TEXT is sent to a cloud model
 * (spec: docs/proposals/market-pii-redact.md). Never applied to stored transcripts.
 */
object PiiRedactor {
	const val EMAIL = "[Email]"
	const val PHONE = "[電話]"
	const val ID = "[證號]"
	const val CARD = "[卡號]"

	private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
	private val card = Regex("""(?<![\d-])(?:\d[ -]?){12,18}\d(?![\d-])""")
	private val twId = Regex("""(?<![A-Za-z0-9])[A-Z][1289]\d{8}(?!\d)""")
	private val mobile = Regex("""(?<![\d+])(?:\+886[ -]?|0)9\d{2}[ -]?\d{3}[ -]?\d{3}(?!\d)""")
	private val landline = Regex("""(?<!\d)(?:\(0\d{1,2}\)[ -]?\d{3,4}[ -]?\d{4}|0\d{1,2}-\d{3,4}-?\d{4})(?!\d)""")

	fun redact(text: String): String {
		var out = email.replace(text, EMAIL)
		out = card.replace(out) { m -> if (luhn(m.value.filter(Char::isDigit))) CARD else m.value }
		out = twId.replace(out, ID)
		out = mobile.replace(out, PHONE)
		out = landline.replace(out, PHONE)
		return out
	}

	internal fun luhn(digits: String): Boolean {
		if (digits.length !in 13..19) return false
		var sum = 0
		digits.reversed().forEachIndexed { i, c ->
			var d = c - '0'
			if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
			sum += d
		}
		return sum % 10 == 0
	}
}
