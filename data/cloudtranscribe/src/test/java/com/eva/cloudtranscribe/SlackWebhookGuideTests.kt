package com.eva.cloudtranscribe

import android.content.ActivityNotFoundException
import com.eva.cloudtranscribe.slack.SlackWebhookGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlackWebhookGuideTests {

	@Test
	fun officialGuideUrlIsCanonicalHttpsDocsPage() {
		assertEquals(
			"https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/",
			SlackWebhookGuide.URL,
		)
		assertTrue(SlackWebhookGuide.URL.startsWith("https://"))
		assertTrue(SlackWebhookGuide.URL.endsWith("/"))
		assertFalse(SlackWebhookGuide.URL.contains("hooks.slack.com"))
	}

	@Test
	fun viewIntentCanBeCreatedForTheOfficialGuide() {
		// Intent getters are stubs in unit tests; only assert the constructor does not throw.
		assertNotNull(SlackWebhookGuide.viewIntent())
	}

	@Test
	fun openInvokesStartActivityAndReturnsFalseWhenNoBrowserIsAvailable() {
		var started = 0
		assertTrue(SlackWebhookGuide.open { started += 1 })
		assertEquals(1, started)
		assertFalse(SlackWebhookGuide.open { throw ActivityNotFoundException("no browser") })
	}
}
