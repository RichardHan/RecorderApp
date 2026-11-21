package com.eva.feature_settings.cloudai

import com.eva.cloudtranscribe.live.LiveCloudSettings
import com.eva.feature_settings.composables.cloudai.slackPushIntervalSeconds
import org.junit.Assert.assertEquals
import org.junit.Test

class SlackPushIntervalTest {
	@Test
	fun usesConfiguredLiveSummaryIntervalWithinRange() {
		assertEquals(30, slackPushIntervalSeconds(LiveCloudSettings.DEFAULT_SUMMARY_INTERVAL))
		assertEquals(45, slackPushIntervalSeconds(45))
		assertEquals(LiveCloudSettings.MIN_SUMMARY_INTERVAL, slackPushIntervalSeconds(1))
		assertEquals(LiveCloudSettings.MAX_SUMMARY_INTERVAL, slackPushIntervalSeconds(99999))
	}
}
