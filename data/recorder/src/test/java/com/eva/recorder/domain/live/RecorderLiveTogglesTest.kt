package com.eva.recorder.domain.live

import com.eva.cloudtranscribe.live.LiveToggleAction
import com.eva.cloudtranscribe.live.LiveToggleInputs
import com.eva.cloudtranscribe.live.LiveToggleKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderLiveTogglesTest {

	private fun ready(
		localDefaultOn: Boolean = false,
		cloudDefaultOn: Boolean = true,
		cloudHasConsent: Boolean = true,
		slackDefaultOn: Boolean = true,
		slackHasConsent: Boolean = true,
		hasLocalModel: Boolean = true,
		cloudConfigured: Boolean = true,
		slackConfigured: Boolean = true,
	) = LiveToggleInputs(
		localDefaultOn = localDefaultOn,
		hasLocalModel = hasLocalModel,
		cloudDefaultOn = cloudDefaultOn,
		cloudConfigured = cloudConfigured,
		cloudHasConsent = cloudHasConsent,
		slackDefaultOn = slackDefaultOn,
		slackConfigured = slackConfigured,
		slackHasConsent = slackHasConsent,
	)

	private class Harness(initial: LiveToggleInputs) {
		var inputs = initial
		val persisted = mutableListOf<Pair<LiveToggleKind, Boolean>>()
		var cloudConsentWrites = 0
		var slackConsentWrites = 0
		val toggles = RecorderLiveToggles(
			readInputs = { inputs },
			persistDefault = { kind, on ->
				persisted += kind to on
				inputs = when (kind) {
					LiveToggleKind.LOCAL -> inputs.copy(localDefaultOn = on)
					LiveToggleKind.CLOUD -> inputs.copy(cloudDefaultOn = on && inputs.cloudHasConsent)
					LiveToggleKind.SLACK -> if (on) {
						inputs.copy(slackDefaultOn = inputs.slackHasConsent, slackHasConsent = inputs.slackHasConsent)
					} else {
						// Turning Slack off clears consent, matching SlackSyncSettingsRepository.
						inputs.copy(slackDefaultOn = false, slackHasConsent = false)
					}
				}
			},
			persistCloudConsent = {
				cloudConsentWrites += 1
				inputs = inputs.copy(cloudDefaultOn = true, cloudHasConsent = true)
			},
			persistSlackConsent = {
				slackConsentWrites += 1
				inputs = inputs.copy(slackDefaultOn = true, slackHasConsent = true)
			},
		)
	}

	@Test
	fun mid_recording_toggle_writes_start_defaults_and_next_recording_uses_them() = runTest {
		val h = Harness(ready())
		h.toggles.beginRecording()
		assertFalse(h.toggles.ui.value.localOn)
		assertTrue(h.toggles.ui.value.cloudOn)
		assertTrue(h.toggles.ui.value.slackOn)

		assertEquals(LiveToggleAction.Applied, h.toggles.toggle(LiveToggleKind.LOCAL, true))
		assertEquals(LiveToggleAction.Applied, h.toggles.toggle(LiveToggleKind.CLOUD, false))
		assertEquals(LiveToggleAction.Applied, h.toggles.toggle(LiveToggleKind.SLACK, false))
		assertEquals(
			listOf(
				LiveToggleKind.LOCAL to true,
				LiveToggleKind.CLOUD to false,
				LiveToggleKind.SLACK to false,
			),
			h.persisted,
		)
		assertTrue(h.inputs.localDefaultOn)
		assertFalse(h.inputs.cloudDefaultOn)
		assertFalse(h.inputs.slackDefaultOn)

		h.toggles.endRecording()
		assertTrue(h.toggles.ui.value.localOn)
		assertFalse(h.toggles.ui.value.cloudOn)
		assertFalse(h.toggles.ui.value.slackOn)
		assertFalse(h.toggles.ui.value.inRecording)

		h.toggles.beginRecording()
		assertTrue(h.toggles.ui.value.localOn)
		assertFalse(h.toggles.ui.value.cloudOn)
		assertFalse(h.toggles.ui.value.slackOn)
		assertTrue(h.toggles.ui.value.inRecording)
	}

	@Test
	fun turning_off_persists_off() = runTest {
		val h = Harness(ready(localDefaultOn = true))
		h.toggles.beginRecording()
		assertEquals(LiveToggleAction.Applied, h.toggles.toggle(LiveToggleKind.LOCAL, false))
		assertEquals(listOf(LiveToggleKind.LOCAL to false), h.persisted)
		assertFalse(h.inputs.localDefaultOn)
		h.toggles.endRecording()
		h.toggles.beginRecording()
		assertFalse(h.toggles.ui.value.localOn)
	}

	@Test
	fun cloud_on_without_consent_does_not_persist_or_bypass() = runTest {
		val h = Harness(ready(cloudDefaultOn = false, cloudHasConsent = false))
		h.toggles.beginRecording()
		assertFalse(h.toggles.ui.value.cloudOn)
		assertEquals(LiveToggleAction.NeedsCloudConsent, h.toggles.toggle(LiveToggleKind.CLOUD, true))
		assertTrue(h.persisted.isEmpty())
		assertFalse(h.inputs.cloudDefaultOn)
		assertFalse(h.toggles.ui.value.cloudOn)

		h.toggles.acceptCloudConsent()
		assertEquals(1, h.cloudConsentWrites)
		assertTrue(h.inputs.cloudHasConsent)
		assertTrue(h.inputs.cloudDefaultOn)
		assertTrue(h.toggles.ui.value.cloudOn)

		h.toggles.endRecording()
		h.toggles.beginRecording()
		assertTrue(h.toggles.ui.value.cloudOn)
	}

	@Test
	fun slack_on_without_consent_does_not_persist_or_bypass() = runTest {
		val h = Harness(ready(slackDefaultOn = false, slackHasConsent = false))
		h.toggles.beginRecording()
		assertFalse(h.toggles.ui.value.slackOn)
		assertTrue(h.toggles.ui.value.slackEnabled)
		assertEquals(LiveToggleAction.NeedsSlackConsent, h.toggles.toggle(LiveToggleKind.SLACK, true))
		assertTrue(h.persisted.isEmpty())
		assertFalse(h.inputs.slackDefaultOn)
		assertFalse(h.toggles.ui.value.slackOn)

		h.toggles.acceptSlackConsent()
		assertEquals(1, h.slackConsentWrites)
		assertTrue(h.inputs.slackHasConsent)
		assertTrue(h.inputs.slackDefaultOn)
		assertTrue(h.toggles.ui.value.slackOn)

		h.toggles.endRecording()
		h.toggles.beginRecording()
		assertTrue(h.toggles.ui.value.slackOn)
	}

	@Test
	fun remembered_on_cannot_start_slack_or_cloud_if_consent_is_missing() = runTest {
		val h = Harness(
			ready(cloudDefaultOn = true, cloudHasConsent = false, slackDefaultOn = true, slackHasConsent = false),
		)
		h.toggles.beginRecording()
		assertFalse(h.toggles.ui.value.cloudOn)
		assertFalse(h.toggles.ui.value.slackOn)
		assertTrue(h.persisted.isEmpty())
	}

	@Test
	fun turning_slack_off_clears_consent_so_turning_on_again_asks() = runTest {
		val h = Harness(ready())
		h.toggles.beginRecording()
		assertEquals(LiveToggleAction.Applied, h.toggles.toggle(LiveToggleKind.SLACK, false))
		assertFalse(h.inputs.slackHasConsent)
		assertTrue(h.toggles.ui.value.slackEnabled)
		assertEquals(LiveToggleAction.NeedsSlackConsent, h.toggles.toggle(LiveToggleKind.SLACK, true))
		assertEquals(listOf(LiveToggleKind.SLACK to false), h.persisted)
		assertFalse(h.toggles.ui.value.slackOn)
	}
}
