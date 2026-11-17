package com.eva.recorder.domain.live

import com.eva.cloudtranscribe.live.LiveToggleAction
import com.eva.cloudtranscribe.live.LiveToggleInputs
import com.eva.cloudtranscribe.live.LiveToggleKind
import com.eva.cloudtranscribe.live.LiveTogglePending
import com.eva.cloudtranscribe.live.LiveToggleSnapshot
import com.eva.cloudtranscribe.live.RecorderLiveTogglePolicy
import com.eva.cloudtranscribe.live.LiveCloudSettingsRepository
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.slack.SlackSyncSettingsRepository
import com.eva.cloudtranscribe.slack.SlackWebhookStore
import com.eva.datastore.domain.repository.TranscriptionSettingsRepo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI snapshot for the three recorder chips. */
data class RecorderLiveToggleUi(
	val snapshot: LiveToggleSnapshot = LiveToggleSnapshot(),
	val inputs: LiveToggleInputs = LiveToggleInputs(),
	val localEnabled: Boolean = true,
	val cloudEnabled: Boolean = false,
	val slackEnabled: Boolean = false,
	val localHint: String? = null,
	val cloudHint: String? = null,
	val slackHint: String? = null,
) {
	val localOn: Boolean get() = snapshot.localOn
	val cloudOn: Boolean get() = snapshot.cloudOn
	val slackOn: Boolean get() = snapshot.slackOn
	val inRecording: Boolean get() = snapshot.inRecording
}

/**
 * Per-recording on/off for 本機字幕 / 雲端即時 / Slack. Defaults come from settings at each new
 * recording; mid-recording toggles do not write those settings (consent acceptance is persisted).
 */
@Singleton
class RecorderLiveToggles @Inject constructor(
	private val transcriptionSettings: TranscriptionSettingsRepo,
	private val liveCloudSettings: LiveCloudSettingsRepository,
	private val cloudSettings: CloudSettingsRepository,
	private val asrKeys: ApiKeyStore,
	private val slackSettings: SlackSyncSettingsRepository,
	private val webhookStore: SlackWebhookStore,
) {
	private val lock = Mutex()
	private var pending: LiveTogglePending? = null
	private var snapshot = LiveToggleSnapshot()
	private var inputs = LiveToggleInputs()

	private val _ui = MutableStateFlow(RecorderLiveToggleUi())
	val ui: StateFlow<RecorderLiveToggleUi> = _ui.asStateFlow()

	suspend fun refreshInputs() {
		lock.withLock {
			inputs = currentInputs()
			if (!snapshot.inRecording) {
				snapshot = RecorderLiveTogglePolicy.idleState(inputs, pending)
			}
			publishLocked()
		}
	}

	/** Capture settings defaults (plus any pre-start pending overrides) for a new recording. */
	suspend fun beginRecording() {
		lock.withLock {
			inputs = currentInputs()
			snapshot = RecorderLiveTogglePolicy.startState(inputs, pending)
			pending = null
			publishLocked()
		}
	}

	/** Recording finished or cancelled: chips fall back to settings defaults (no carry-over). */
	suspend fun endRecording() {
		lock.withLock {
			pending = null
			inputs = currentInputs()
			snapshot = RecorderLiveTogglePolicy.idleState(inputs)
			publishLocked()
		}
	}

	suspend fun toggle(kind: LiveToggleKind, wantOn: Boolean): LiveToggleAction = lock.withLock {
		inputs = currentInputs()
		val (action, next) = RecorderLiveTogglePolicy.onToggle(kind, wantOn, inputs, snapshot)
		if (action == LiveToggleAction.Applied) {
			snapshot = next
			if (!snapshot.inRecording) pending = pendingFor(next)
			publishLocked()
		}
		action
	}

	/** Persist consent (and enable the setting default) then turn cloud on for this session/pending. */
	suspend fun acceptCloudConsent() {
		val now = System.currentTimeMillis()
		liveCloudSettings.update { it.copy(enabled = true, consentAcceptedAtMillis = now) }
		lock.withLock {
			inputs = currentInputs()
			snapshot = RecorderLiveTogglePolicy.afterCloudConsent(inputs, snapshot)
			if (!snapshot.inRecording) pending = pendingFor(snapshot)
			publishLocked()
		}
	}

	private fun pendingFor(s: LiveToggleSnapshot) = LiveTogglePending(
		localOn = s.localOn,
		cloudOn = s.cloudOn,
		slackOn = s.slackOn,
	)

	private suspend fun currentInputs(): LiveToggleInputs {
		val local = runCatching { transcriptionSettings.setting() }.getOrNull()
		val live = runCatching { liveCloudSettings.current() }.getOrDefault(com.eva.cloudtranscribe.live.LiveCloudSettings())
		val cloud = runCatching { cloudSettings.current() }.getOrNull()
		val hasKey = runCatching { asrKeys.read() }.getOrNull() != null
		val slack = runCatching { slackSettings.current() }.getOrDefault(com.eva.cloudtranscribe.slack.SlackSyncSettings())
		val hasWebhook = webhookStore.hasKey.value
		return LiveToggleInputs(
			localDefaultOn = local?.isEnabled == true,
			hasLocalModel = local?.modelId != null,
			cloudDefaultOn = live.isActive,
			cloudConfigured = cloud?.validate(hasKey) == null,
			cloudHasConsent = live.consentAcceptedAtMillis != null,
			cloudTranscribeIntervalSec = live.transcribeIntervalSec,
			cloudSummaryIntervalSec = live.summaryIntervalSec,
			cloudSummaryModel = live.summaryModel.ifBlank { com.eva.cloudtranscribe.live.LiveCloudSettings.DEFAULT_SUMMARY_MODEL },
			cloudEnhanceContextQuality = live.enhanceContextQuality,
			slackDefaultOn = slack.isActive && hasWebhook,
			slackConfigured = slack.isActive && hasWebhook,
		)
	}

	private fun publishLocked() {
		_ui.value = RecorderLiveToggleUi(
			snapshot = snapshot,
			inputs = inputs,
			localEnabled = RecorderLiveTogglePolicy.chipEnabled(LiveToggleKind.LOCAL, inputs),
			cloudEnabled = RecorderLiveTogglePolicy.chipEnabled(LiveToggleKind.CLOUD, inputs),
			slackEnabled = RecorderLiveTogglePolicy.chipEnabled(LiveToggleKind.SLACK, inputs),
			localHint = RecorderLiveTogglePolicy.hint(LiveToggleKind.LOCAL, inputs, snapshot),
			cloudHint = RecorderLiveTogglePolicy.hint(LiveToggleKind.CLOUD, inputs, snapshot),
			slackHint = RecorderLiveTogglePolicy.hint(LiveToggleKind.SLACK, inputs, snapshot),
		)
	}
}
