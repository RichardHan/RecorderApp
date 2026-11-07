package com.eva.recorder.domain.recorder

import kotlinx.coroutines.flow.Flow

data class LiveTranscript(
	val modelId: String,
	val engine: String,
	val text: String,
	val decodedSegments: Int,
	val failedSegments: Int,
	val droppedMillis: Long,
	val complete: Boolean,
)

interface TranscriptionProvider {

	val transcription: Flow<LiveTranscriptState>

	/** Offline finalized lines with start time in ms on the recorded-audio timeline (sentence-mode engines only). */
	val offlineTimedLines: Flow<List<Pair<Long, String>>> get() = kotlinx.coroutines.flow.flowOf(emptyList())

	suspend fun initTranscriptions()

	/** Flushes the tail, waits for pending recognition and returns the full live transcript (null when disabled). */
	suspend fun finishTranscription(): LiveTranscript? = null

	fun transcriptCleanUp()
}
