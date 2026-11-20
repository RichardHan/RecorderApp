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
 * Per-recording on/off for 本機字幕 / 雲端即時 / Slack. Defaults come from the same settings as
 * 「錄音開始時預設」. An applied chip toggle is written back there so the next recording starts
 * from the last-used state. Missing cloud/Slack consent or configuration still blocks turning on.
 */
@Singleton
class RecorderLiveToggles internal constructor(
	private val readInputs: suspend () -> LiveToggleInputs,
	private val persistDefault: suspend (LiveToggleKind, Boolean) -> Unit,
	private val persistCloudConsent: suspend () -> Unit,
	private val persistSlackConsent: suspend () -> Unit,
) {
	@Inject
	constructor(
		transcriptionSettings: TranscriptionSettingsRepo,
		liveCloudSettings: LiveCloudSettingsRepository,
		cloudSettings: CloudSettingsRepository,
		asrKeys: ApiKeyStore,
		slackSettings: SlackSyncSettingsRepository,
		webhookStore: SlackWebhookStore,
	) : this(
		readInputs = {
			currentInputsFrom(
				transcriptionSettings, liveCloudSettings, cloudSettings, asrKeys, slackSettings, webhookStore,
			)
		},
		persistDefault = { kind, on ->
			when (kind) {
				LiveToggleKind.LOCAL -> transcriptionSettings.onEnableOrDisable(on)
				LiveToggleKind.CLOUD -> liveCloudSettings.update { cur -> cur.copy(enabled = on) }
				LiveToggleKind.SLACK -> slackSettings.update { cur -> cur.copy(enabled = on) }
			}
		},
		persistCloudConsent = {
			val now = System.currentTimeMillis()
			liveCloudSettings.update { it.copy(enabled = true, consentAcceptedAtMillis = now) }
		},
		persistSlackConsent = {
			val now = System.currentTimeMillis()
			slackSettings.update { it.copy(enabled = true, consentAtMillis = now) }
		},
	)

	private val lock = Mutex()
	private var pending: LiveTogglePending? = null
	private var snapshot = LiveToggleSnapshot()
	private var inputs = LiveToggleInputs()

	private val _ui = MutableStateFlow(RecorderLiveToggleUi())
	val ui: StateFlow<RecorderLiveToggleUi> = _ui.asStateFlow()

	suspend fun refreshInputs() {
		lock.withLock {
			inputs = readInputs()
			if (!snapshot.inRecording) {
				snapshot = RecorderLiveTogglePolicy.idleState(inputs, pending)
			}
			publishLocked()
		}
	}

	/** Capture settings defaults (plus any pre-start pending overrides) for a new recording. */
	suspend fun beginRecording() {
		lock.withLock {
			inputs = readInputs()
			snapshot = RecorderLiveTogglePolicy.startState(inputs, pending)
			pending = null
			publishLocked()
		}
	}

	/** Recording finished or cancelled: chips show the (possibly updated) settings defaults. */
	suspend fun endRecording() {
		lock.withLock {
			pending = null
			inputs = readInputs()
			snapshot = RecorderLiveTogglePolicy.idleState(inputs)
			publishLocked()
		}
	}

	suspend fun toggle(kind: LiveToggleKind, wantOn: Boolean): LiveToggleAction = lock.withLock {
		inputs = readInputs()
		val (action, next) = RecorderLiveTogglePolicy.onToggle(kind, wantOn, inputs, snapshot)
		if (action == LiveToggleAction.Applied) {
			snapshot = next
			persistDefault(kind, wantOn)
			inputs = readInputs()
			if (!snapshot.inRecording) pending = pendingFor(next)
			publishLocked()
		}
		action
	}

	/** Persist consent (and enable the setting default) then turn cloud on for this session. */
	suspend fun acceptCloudConsent() {
		persistCloudConsent()
		lock.withLock {
			inputs = readInputs()
			snapshot = RecorderLiveTogglePolicy.afterCloudConsent(inputs, snapshot)
			if (!snapshot.inRecording) pending = pendingFor(snapshot)
			publishLocked()
		}
	}

	/** Persist Slack consent (and enable the setting default) then turn Slack on for this session. */
	suspend fun acceptSlackConsent() {
		persistSlackConsent()
		lock.withLock {
			inputs = readInputs()
			snapshot = RecorderLiveTogglePolicy.afterSlackConsent(inputs, snapshot)
			if (!snapshot.inRecording) pending = pendingFor(snapshot)
			publishLocked()
		}
	}

	private fun pendingFor(s: LiveToggleSnapshot) = LiveTogglePending(
		localOn = s.localOn,
		cloudOn = s.cloudOn,
		slackOn = s.slackOn,
	)

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

	private companion object {
		suspend fun currentInputsFrom(
			transcriptionSettings: TranscriptionSettingsRepo,
			liveCloudSettings: LiveCloudSettingsRepository,
			cloudSettings: CloudSettingsRepository,
			asrKeys: ApiKeyStore,
			slackSettings: SlackSyncSettingsRepository,
			webhookStore: SlackWebhookStore,
		): LiveToggleInputs {
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
				slackConfigured = hasWebhook,
				slackHasConsent = slack.consentAtMillis != null,
			)
		}
	}
}
