package io.github.richardhan.voicenote

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleDocumentTest {
	@Test fun usesSourceTimesAndEscapesMarkupInWebVtt() {
		val text = subtitleText(listOf(TimedText(3601.25, 3603.125, "A < B & C")), DocumentFormat.WebVtt)
		assertEquals("WEBVTT\n\n1\n01:00:01.250 --> 01:00:03.125\nA &lt; B &amp; C\n\n", text)
	}

	@Test fun srtUsesCommaAndKeepsRoundedCueNonempty() {
		assertEquals("1\n00:00:01,000 --> 00:00:01,001\nHi\n\n",
			subtitleText(listOf(TimedText(1.0001, 1.0002, "Hi")), DocumentFormat.SubRip))
	}
}
