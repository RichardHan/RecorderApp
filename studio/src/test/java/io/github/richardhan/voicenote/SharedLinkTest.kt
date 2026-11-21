package io.github.richardhan.voicenote

import org.junit.Assert.*
import org.junit.Test

class SharedLinkTest {
	@Test fun extractsSingleYouTubeLinkFromCaption() {
		assertEquals("https://youtu.be/abcdefghijk?t=5", sharedHttpsLink("週會錄影\nhttps://youtu.be/abcdefghijk?t=5"))
	}
	@Test fun preservesSignedParametersAndEscapedPath() {
		assertEquals("https://audio.example/a%20b.mp3?sig=a%2Fb&v=2",
			sharedHttpsLink(" HTTPS://audio.example/a%20b.mp3?sig=a%2Fb&v=2 "))
	}
	@Test fun rejectsAmbiguousOrUnsupportedText() {
		for (text in listOf("no link here", "https://one.test https://two.test", "file:///private/a",
			"http://audio.test/example", "https://user:pass@audio.test/a", "https:///bad")) {
			assertNull(text, sharedHttpsLink(text))
		}
	}
}
