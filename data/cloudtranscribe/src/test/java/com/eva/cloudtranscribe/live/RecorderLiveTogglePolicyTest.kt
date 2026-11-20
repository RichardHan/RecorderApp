package com.eva.cloudtranscribe.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderLiveTogglePolicyTest {

	private val ready = LiveToggleInputs(
		localDefaultOn = false,
		hasLocalModel = true,
		cloudDefaultOn = true,
		cloudConfigured = true,
		cloudHasConsent = true,
		slackDefaultOn = true,
		slackConfigured = true,
		slackHasConsent = true,
	)

	@Test
	fun defaults_local_off_cloud_and_slack_follow_settings() {
		val start = RecorderLiveTogglePolicy.startState(ready)
		assertFalse(start.localOn)
		assertTrue(start.cloudOn)
		assertTrue(start.slackOn)
		assertTrue(start.inRecording)
	}

	@Test
	fun start_does_not_turn_on_cloud_without_consent_or_config() {
		assertFalse(RecorderLiveTogglePolicy.startState(ready.copy(cloudHasConsent = false)).cloudOn)
		assertFalse(RecorderLiveTogglePolicy.startState(ready.copy(cloudConfigured = false, cloudDefaultOn = true)).cloudOn)
		assertFalse(RecorderLiveTogglePolicy.startState(ready.copy(cloudDefaultOn = false)).cloudOn)
	}

	@Test
	fun start_does_not_turn_on_slack_when_unset() {
		assertFalse(RecorderLiveTogglePolicy.startState(ready.copy(slackConfigured = false, slackDefaultOn = true)).slackOn)
		assertFalse(RecorderLiveTogglePolicy.startState(ready.copy(slackDefaultOn = false)).slackOn)
	}

	@Test
	fun start_does_not_turn_on_local_without_a_model() {
		assertFalse(RecorderLiveTogglePolicy.startState(ready.copy(localDefaultOn = true, hasLocalModel = false)).localOn)
		assertTrue(RecorderLiveTogglePolicy.startState(ready.copy(localDefaultOn = true, hasLocalModel = true)).localOn)
	}

	@Test
	fun pending_overrides_apply_only_for_the_next_start() {
		val pending = LiveTogglePending(localOn = true, cloudOn = false, slackOn = false)
		val start = RecorderLiveTogglePolicy.startState(ready, pending)
		assertTrue(start.localOn)
		assertFalse(start.cloudOn)
		assertFalse(start.slackOn)
		// a later recording without pending goes back to defaults
		val next = RecorderLiveTogglePolicy.startState(ready, pending = null)
		assertFalse(next.localOn)
		assertTrue(next.cloudOn)
		assertTrue(next.slackOn)
	}

	@Test
	fun mid_recording_toggles_do_not_depend_on_defaults() {
		val start = RecorderLiveTogglePolicy.startState(ready)
		val (localAct, afterLocal) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.LOCAL, true, ready, start)
		assertEquals(LiveToggleAction.Applied, localAct)
		assertTrue(afterLocal.localOn)
		val (cloudAct, afterCloud) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.CLOUD, false, ready, afterLocal)
		assertEquals(LiveToggleAction.Applied, cloudAct)
		assertFalse(afterCloud.cloudOn)
		val (slackAct, afterSlack) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.SLACK, false, ready, afterCloud)
		assertEquals(LiveToggleAction.Applied, slackAct)
		assertFalse(afterSlack.slackOn)
		// without updated settings, idle falls back to the (unchanged) defaults
		val idle = RecorderLiveTogglePolicy.idleState(ready)
		assertFalse(idle.localOn)
		assertTrue(idle.cloudOn)
		assertTrue(idle.slackOn)
		assertFalse(idle.inRecording)
	}

	@Test
	fun applied_toggles_are_persisted_so_the_next_start_uses_last_used_state() {
		val start = RecorderLiveTogglePolicy.startState(ready)
		val (localAct, afterLocal) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.LOCAL, true, ready, start)
		val (cloudAct, afterCloud) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.CLOUD, false, ready, afterLocal)
		val (slackAct, afterSlack) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.SLACK, false, ready, afterCloud)
		assertTrue(RecorderLiveTogglePolicy.shouldPersistDefault(localAct))
		assertTrue(RecorderLiveTogglePolicy.shouldPersistDefault(cloudAct))
		assertTrue(RecorderLiveTogglePolicy.shouldPersistDefault(slackAct))
		// simulate writing those choices back to the start-default settings
		val remembered = ready.copy(localDefaultOn = afterSlack.localOn, cloudDefaultOn = afterSlack.cloudOn, slackDefaultOn = afterSlack.slackOn)
		val next = RecorderLiveTogglePolicy.startState(remembered)
		assertTrue(next.localOn)
		assertFalse(next.cloudOn)
		assertFalse(next.slackOn)
	}

	@Test
	fun remembered_on_does_not_bypass_missing_consent_or_configuration() {
		assertFalse(
			RecorderLiveTogglePolicy.startState(
				ready.copy(cloudDefaultOn = true, cloudHasConsent = false),
			).cloudOn,
		)
		assertFalse(
			RecorderLiveTogglePolicy.startState(
				ready.copy(cloudDefaultOn = true, cloudConfigured = false),
			).cloudOn,
		)
		assertFalse(
			RecorderLiveTogglePolicy.startState(
				ready.copy(slackDefaultOn = true, slackHasConsent = false),
			).slackOn,
		)
		assertFalse(
			RecorderLiveTogglePolicy.startState(
				ready.copy(slackDefaultOn = true, slackConfigured = false),
			).slackOn,
		)
		assertFalse(
			RecorderLiveTogglePolicy.startState(
				ready.copy(localDefaultOn = true, hasLocalModel = false),
			).localOn,
		)
	}

	@Test
	fun first_slack_on_without_consent_requests_consent_and_is_not_persisted() {
		val inputs = ready.copy(slackDefaultOn = false, slackHasConsent = false)
		assertTrue(RecorderLiveTogglePolicy.chipEnabled(LiveToggleKind.SLACK, inputs))
		val (action, next) = RecorderLiveTogglePolicy.onToggle(
			LiveToggleKind.SLACK, true, inputs, RecorderLiveTogglePolicy.startState(inputs),
		)
		assertEquals(LiveToggleAction.NeedsSlackConsent, action)
		assertFalse(next.slackOn)
		assertFalse(RecorderLiveTogglePolicy.shouldPersistDefault(action))
		val consented = inputs.copy(slackHasConsent = true)
		assertTrue(RecorderLiveTogglePolicy.afterSlackConsent(consented, next).slackOn)
	}

	@Test
	fun turning_local_on_without_a_model_asks_to_pick_one() {
		val inputs = ready.copy(hasLocalModel = false)
		val start = RecorderLiveTogglePolicy.startState(inputs)
		val (action, next) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.LOCAL, true, inputs, start)
		assertEquals(LiveToggleAction.NeedsLocalModel, action)
		assertFalse(RecorderLiveTogglePolicy.shouldPersistDefault(action))
		assertFalse(next.localOn)
	}

	@Test
	fun first_cloud_on_without_consent_requests_the_existing_dialog() {
		val inputs = ready.copy(cloudDefaultOn = false, cloudHasConsent = false)
		val start = RecorderLiveTogglePolicy.startState(inputs)
		assertFalse(start.cloudOn)
		assertTrue(RecorderLiveTogglePolicy.chipEnabled(LiveToggleKind.CLOUD, inputs))
		val (action, next) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.CLOUD, true, inputs, start)
		assertEquals(LiveToggleAction.NeedsCloudConsent, action)
		assertFalse(RecorderLiveTogglePolicy.shouldPersistDefault(action))
		assertFalse(next.cloudOn)
		val consented = inputs.copy(cloudHasConsent = true)
		val after = RecorderLiveTogglePolicy.afterCloudConsent(consented, next)
		assertTrue(after.cloudOn)
	}

	@Test
	fun cloud_and_slack_chips_disabled_when_unset() {
		val inputs = ready.copy(cloudConfigured = false, slackConfigured = false)
		assertFalse(RecorderLiveTogglePolicy.chipEnabled(LiveToggleKind.CLOUD, inputs))
		assertFalse(RecorderLiveTogglePolicy.chipEnabled(LiveToggleKind.SLACK, inputs))
		assertEquals(
			RecorderLiveTogglePolicy.HINT_UNSET,
			RecorderLiveTogglePolicy.hint(LiveToggleKind.CLOUD, inputs, RecorderLiveTogglePolicy.startState(inputs)),
		)
		assertEquals(
			RecorderLiveTogglePolicy.HINT_UNSET,
			RecorderLiveTogglePolicy.hint(LiveToggleKind.SLACK, inputs, RecorderLiveTogglePolicy.startState(inputs)),
		)
		val (cloud, s1) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.CLOUD, true, inputs, RecorderLiveTogglePolicy.startState(inputs))
		assertEquals(LiveToggleAction.Disabled, cloud)
		assertFalse(s1.cloudOn)
		val (slack, s2) = RecorderLiveTogglePolicy.onToggle(LiveToggleKind.SLACK, true, inputs, s1)
		assertEquals(LiveToggleAction.Disabled, slack)
		assertFalse(s2.slackOn)
	}

	@Test
	fun slack_on_with_cloud_off_shows_key_points_hint() {
		val start = RecorderLiveTogglePolicy.startState(ready, LiveTogglePending(cloudOn = false, slackOn = true))
		assertTrue(start.slackOn)
		assertFalse(start.cloudOn)
		assertEquals(
			RecorderLiveTogglePolicy.HINT_SLACK_NEEDS_CLOUD,
			RecorderLiveTogglePolicy.hint(LiveToggleKind.SLACK, ready, start),
		)
		assertNull(RecorderLiveTogglePolicy.hint(LiveToggleKind.SLACK, ready, start.copy(cloudOn = true)))
	}
}
