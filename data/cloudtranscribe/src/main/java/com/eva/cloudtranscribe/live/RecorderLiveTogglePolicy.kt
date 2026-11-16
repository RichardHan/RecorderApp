package com.eva.cloudtranscribe.live

/** Settings + configuration that decide recording-start defaults and whether a chip can be used. */
data class LiveToggleInputs(
	/** Settings default for on-device live captions. */
	val localDefaultOn: Boolean = false,
	val hasLocalModel: Boolean = false,
	/** Settings default: live cloud is enabled and consent was given. */
	val cloudDefaultOn: Boolean = false,
	/** Cloud ASR endpoint + API key are configured. */
	val cloudConfigured: Boolean = false,
	val cloudHasConsent: Boolean = false,
	val cloudTranscribeIntervalSec: Int = LiveCloudSettings.DEFAULT_TRANSCRIBE_INTERVAL,
	val cloudSummaryIntervalSec: Int = LiveCloudSettings.DEFAULT_SUMMARY_INTERVAL,
	val cloudSummaryModel: String = LiveCloudSettings.DEFAULT_SUMMARY_MODEL,
	/** Settings default: Slack is opted in and a webhook exists. */
	val slackDefaultOn: Boolean = false,
	/** Webhook saved and Slack consent given. */
	val slackConfigured: Boolean = false,
)

/** Per-recording (or upcoming) on/off for the three live features. */
data class LiveToggleSnapshot(
	val localOn: Boolean = false,
	val cloudOn: Boolean = false,
	val slackOn: Boolean = false,
	val inRecording: Boolean = false,
)

/** Overrides chosen on the recorder screen before the next recording starts. */
data class LiveTogglePending(
	val localOn: Boolean? = null,
	val cloudOn: Boolean? = null,
	val slackOn: Boolean? = null,
) {
	fun withLocal(on: Boolean) = copy(localOn = on)
	fun withCloud(on: Boolean) = copy(cloudOn = on)
	fun withSlack(on: Boolean) = copy(slackOn = on)
}

enum class LiveToggleKind { LOCAL, CLOUD, SLACK }

sealed class LiveToggleAction {
	data object Applied : LiveToggleAction()
	/** Chip is disabled (not configured). */
	data object Disabled : LiveToggleAction()
	data object NeedsLocalModel : LiveToggleAction()
	data object NeedsCloudConsent : LiveToggleAction()
}

/**
 * Pure policy for the recorder's 「本機字幕 / 雲端即時 / Slack」 chips.
 * Per-recording on/off never writes settings; each new recording starts from [LiveToggleInputs] defaults.
 */
object RecorderLiveTogglePolicy {
	const val HINT_SLACK_NEEDS_CLOUD = "需開雲端即時才有新重點"
	const val HINT_UNSET = "未設定"
	const val HINT_NO_MODEL = "未選模型"

	fun idleState(inputs: LiveToggleInputs, pending: LiveTogglePending? = null): LiveToggleSnapshot =
		resolve(inputs, pending, inRecording = false)

	fun startState(inputs: LiveToggleInputs, pending: LiveTogglePending? = null): LiveToggleSnapshot =
		resolve(inputs, pending, inRecording = true)

	private fun resolve(inputs: LiveToggleInputs, pending: LiveTogglePending?, inRecording: Boolean): LiveToggleSnapshot {
		val local = (pending?.localOn ?: inputs.localDefaultOn) && inputs.hasLocalModel
		val cloud = (pending?.cloudOn ?: inputs.cloudDefaultOn) && inputs.cloudConfigured && inputs.cloudHasConsent
		val slack = (pending?.slackOn ?: inputs.slackDefaultOn) && inputs.slackConfigured
		return LiveToggleSnapshot(localOn = local, cloudOn = cloud, slackOn = slack, inRecording = inRecording)
	}

	fun chipEnabled(kind: LiveToggleKind, inputs: LiveToggleInputs): Boolean = when (kind) {
		// No model: still tappable so we can show the existing “pick a model” dialog.
		LiveToggleKind.LOCAL -> true
		// Configured but no consent: tappable so the existing cost dialog can be shown.
		LiveToggleKind.CLOUD -> inputs.cloudConfigured
		LiveToggleKind.SLACK -> inputs.slackConfigured
	}

	fun hint(kind: LiveToggleKind, inputs: LiveToggleInputs, snapshot: LiveToggleSnapshot): String? = when (kind) {
		LiveToggleKind.LOCAL -> if (!inputs.hasLocalModel) HINT_NO_MODEL else null
		LiveToggleKind.CLOUD -> if (!inputs.cloudConfigured) HINT_UNSET else null
		LiveToggleKind.SLACK -> when {
			!inputs.slackConfigured -> HINT_UNSET
			snapshot.slackOn && !snapshot.cloudOn -> HINT_SLACK_NEEDS_CLOUD
			else -> null
		}
	}

	fun onToggle(
		kind: LiveToggleKind,
		wantOn: Boolean,
		inputs: LiveToggleInputs,
		snapshot: LiveToggleSnapshot,
	): Pair<LiveToggleAction, LiveToggleSnapshot> = when (kind) {
		LiveToggleKind.LOCAL -> {
			if (wantOn && !inputs.hasLocalModel) LiveToggleAction.NeedsLocalModel to snapshot
			else LiveToggleAction.Applied to snapshot.copy(localOn = wantOn)
		}
		LiveToggleKind.CLOUD -> {
			if (!inputs.cloudConfigured) LiveToggleAction.Disabled to snapshot
			else if (wantOn && !inputs.cloudHasConsent) LiveToggleAction.NeedsCloudConsent to snapshot
			else LiveToggleAction.Applied to snapshot.copy(cloudOn = wantOn)
		}
		LiveToggleKind.SLACK -> {
			if (!inputs.slackConfigured) LiveToggleAction.Disabled to snapshot
			else LiveToggleAction.Applied to snapshot.copy(slackOn = wantOn)
		}
	}

	/** After the user accepts the existing live-cloud consent dialog, turn cloud on for this session/pending. */
	fun afterCloudConsent(inputs: LiveToggleInputs, snapshot: LiveToggleSnapshot): LiveToggleSnapshot =
		if (inputs.cloudConfigured) snapshot.copy(cloudOn = true) else snapshot
}
