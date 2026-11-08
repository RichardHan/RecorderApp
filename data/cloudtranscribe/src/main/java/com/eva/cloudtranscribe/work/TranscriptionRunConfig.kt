package com.eva.cloudtranscribe.work

import com.eva.cloudtranscribe.api.Redactor
import com.eva.cloudtranscribe.engine.EngineConfig
import com.eva.cloudtranscribe.engine.withVocabularyHints
import com.eva.cloudtranscribe.model.CloudSettings
import com.eva.cloudtranscribe.model.Provenance

/** A resumed run keeps its snapshot, including empty hints from records saved before this feature. */
internal fun transcriptionProvenance(settings: CloudSettings, previous: Provenance?, trigger: String): Provenance =
	previous ?: Provenance(
		engine = "cloud", providerType = settings.providerType, endpointHost = Redactor.hostOf(settings.baseUrl),
		model = settings.transcribeModel, reasoningEffort = settings.reasoningEffort.wire,
		chunkSeconds = settings.normalizedChunkSeconds, workers = settings.normalizedWorkers,
		audioFormat = "mp3 16kHz mono 64kbps", trigger = trigger,
		vocabularyHints = settings.normalizedVocabularyHints,
	)

/** Both the first request and the alternate retry prompt use the persisted run hints, not settings. */
internal fun transcriptionEngineConfig(settings: CloudSettings, provenance: Provenance): EngineConfig = EngineConfig(
	chunkSeconds = provenance.chunkSeconds ?: settings.normalizedChunkSeconds,
	workers = provenance.workers ?: settings.normalizedWorkers,
	hallucinationWarning = settings.hallucinationWarning,
	skipSilentChunks = settings.skipSilentChunks,
).withVocabularyHints(provenance.vocabularyHints)
