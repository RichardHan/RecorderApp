package com.eva.cloudtranscribe.title

import com.eva.cloudtranscribe.api.CallOutcome
import com.eva.cloudtranscribe.api.CloudAiClients
import com.eva.cloudtranscribe.api.UrlConnectionTransport
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.PiiRedactor
import com.eva.cloudtranscribe.secure.ApiKeyStore
import com.eva.cloudtranscribe.settings.CloudSettingsRepository
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recordings.domain.models.RecordedVoiceModel
import com.eva.recordings.domain.provider.VoiceRecordingsProvider
import com.eva.utils.Resource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class AutoTitleResult {
	data class Applied(val displayName: String, val source: String) : AutoTitleResult()
	data class Skipped(val reason: String) : AutoTitleResult()
}

/**
 * After a successful post-recording summary with real points, rename the file once if it still has
 * its original auto-generated name. AI title first (summary model, one request); local fallback on failure.
 */
@Singleton
class AutoTitleRenamer internal constructor(
	private val store: TranscriptStore,
	private val settings: suspend () -> CloudSettings,
	private val hasSummaryKey: suspend () -> Boolean,
	private val complete: suspend (CloudSettings, String) -> CallOutcome,
	private val loadRecording: suspend (Long) -> RecordedVoiceModel?,
	private val rename: suspend (RecordedVoiceModel, String) -> Boolean,
	private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
	private val lock = Mutex()

	@Inject
	constructor(
		store: TranscriptStore,
		settingsRepo: CloudSettingsRepository,
		keys: ApiKeyStore,
		recordings: VoiceRecordingsProvider,
	) : this(
		store = store,
		settings = { settingsRepo.current() },
		hasSummaryKey = { !keys.read().isNullOrBlank() },
		complete = complete@{ s, prompt ->
			val key = keys.read() ?: return@complete CallOutcome.Failure("找不到 API Key", retryable = false)
			val client = CloudAiClients.create(s, key, UrlConnectionTransport(), s.summaryModel)
			withContext(Dispatchers.IO) { client.complete(prompt) }
		},
		loadRecording = {
			(recordings.getVoiceRecordingAsResourceFromId(it) as? Resource.Success)?.data
		},
		rename = { recording, newName ->
			when (val res = recordings.renameRecording(recording, newName).first { it !is Resource.Loading }) {
				is Resource.Success -> true
				else -> false
			}
		},
	)

	suspend fun applyIfEligible(recordingId: Long): AutoTitleResult = lock.withLock {
		if (recordingId <= 0L) return AutoTitleResult.Skipped("no-recording")
		val s = settings()
		val marker = store.autoTitleMarker(recordingId) ?: AutoTitleMarker()
		if (marker.applied) return AutoTitleResult.Skipped("already")
		if (!s.autoTitleAfterSummary) return AutoTitleResult.Skipped("setting-off")
		val recording = loadRecording(recordingId) ?: return AutoTitleResult.Skipped("missing")
		val display = recording.displayName.ifBlank { recording.title }
		val usable = TitleSuggestion.latestUsable(store.summaries(recordingId))
		if (!AutoTitlePolicy.shouldAttempt(s.autoTitleAfterSummary, marker.applied, display, usable)) {
			return AutoTitleResult.Skipped(if (usable == null) "no-points" else "not-original")
		}
		val summaryText = usable!!.text
		var requests = marker.requestCount
		var requestAt = marker.requestAtMillis
		val now = nowMillis()
		val attempt = requestAiTitle(s, summaryText)
		if (attempt.requested) {
			requests += 1
			requestAt = requestAt + now
		}
		val aiReply = attempt.reply
		val chosen = AutoTitlePolicy.chooseTitle(aiReply, summaryText)
		if (chosen == null) {
			store.saveAutoTitleMarker(recordingId, marker.copy(requestCount = requests, requestAtMillis = requestAt, updatedAtMillis = now))
			return AutoTitleResult.Skipped("no-title")
		}
		val (title, source) = chosen
		val ext = AutoRecordingName.extensionOf(display)
		val base = AutoRecordingName.format(recording.recordedAt, title, ext)
			?: run {
				store.saveAutoTitleMarker(recordingId, marker.copy(requestCount = requests, requestAtMillis = requestAt, updatedAtMillis = now))
				return AutoTitleResult.Skipped("no-title")
			}
		if (base == display) {
			store.saveAutoTitleMarker(
				recordingId,
				marker.copy(applied = true, displayName = display, source = source, requestCount = requests, requestAtMillis = requestAt, updatedAtMillis = now),
			)
			return AutoTitleResult.Applied(display, source)
		}
		var appliedName: String? = null
		for (attempt in 1..MAX_COLLISION) {
			val candidate = AutoRecordingName.withCollisionSuffix(base, attempt)
			if (candidate == display) { appliedName = display; break }
			if (rename(recording, candidate)) { appliedName = candidate; break }
		}
		if (appliedName == null) {
			store.saveAutoTitleMarker(recordingId, marker.copy(requestCount = requests, requestAtMillis = requestAt, updatedAtMillis = now))
			return AutoTitleResult.Skipped("rename-failed")
		}
		store.saveAutoTitleMarker(
			recordingId,
			AutoTitleMarker(
				applied = true,
				previousDisplayName = display,
				displayName = appliedName,
				source = source,
				requestCount = requests,
				requestAtMillis = requestAt,
				updatedAtMillis = now,
			),
		)
		AutoTitleResult.Applied(appliedName, source)
	}

	private suspend fun requestAiTitle(s: CloudSettings, summaryText: String): AiTitleAttempt {
		if (s.baseUrl.isBlank() || s.summaryModel.isBlank() || !hasSummaryKey()) return AiTitleAttempt(null, requested = false)
		val text = if (s.redactPiiForCloud) PiiRedactor.redact(summaryText) else summaryText
		val reply = when (val o = runCatching { complete(s, TitleSuggestion.prompt(text)) }.getOrNull()) {
			is CallOutcome.Text -> o.text
			else -> null
		}
		return AiTitleAttempt(reply, requested = true)
	}

	private data class AiTitleAttempt(val reply: String?, val requested: Boolean)

	private companion object {
		const val MAX_COLLISION = 9
	}
}
