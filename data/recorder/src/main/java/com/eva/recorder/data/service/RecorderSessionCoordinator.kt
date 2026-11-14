package com.eva.recorder.data.service

import android.util.Log
import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.recorder.data.recorder.DetachedCloudFinish
import com.eva.recorder.data.recorder.LiveCloudCoordinator
import com.eva.recorder.domain.models.RecorderState
import com.eva.recorder.domain.recorder.TranscriptionProvider
import com.eva.recorder.domain.recorder.VoiceRecorder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalTime

private const val TAG = "RECORDER_SESSION_COORD"

/**
 * Coordinates recorder start/pause/resume/stop/cancel transitions with offline and live-cloud transcription so:
 * - Duplicate start requests while a recording is active or preparing never reset live transcripts or cloud sessions.
 * - Live-cloud initialization and stream subscription complete before audio capture starts emitting PCM frames.
 * - Stop and cancel actions serialize cleanly with startup so cloud transcript persistence is never skipped.
 */
internal class RecorderSessionCoordinator(
	private val recorder: VoiceRecorder,
	private val transcriber: TranscriptionProvider,
	private val liveCloud: LiveCloudCoordinator,
	private val transcriptStore: TranscriptStore,
) {
	/** Serializes recorder lifecycle commands while keeping post-stop cloud HTTP flush outside the lock. */
	private val commandLock = Mutex()

	/** Serializes "save previous recording's live transcript" with "reset for a new recording". */
	private val transcriptLock = Mutex()

	private val pendingStarts = AtomicInteger(0)

	val hasPendingStart: Boolean
		get() = pendingStarts.get() > 0

	suspend fun startRecording(): Boolean {
		pendingStarts.incrementAndGet()
		try {
			return commandLock.withLock {
				var initializedSession = false
				val started = recorder.startRecording(
					onBeforeCapture = {
						initializedSession = true
						// per-recording reset; waits for an earlier recording's offline transcript save (same lock)
						// so its text is saved first and can never leak into this recording
						transcriptLock.withLock {
							runCatching { transcriber.beginNewRecording() }
								.onFailure { if (it is CancellationException) throw it else Log.e(TAG, "transcript reset failed", it) }
						}
						// advanced live cloud mode (default OFF -> returns without any request);
						// initialized and subscribed before capture starts so replay-0 stream loses no initial audio
						runCatching { liveCloud.start() }
							.onFailure { if (it is CancellationException) throw it else Log.e(TAG, "live cloud start failed", it) }
					},
				)
				if (!started && initializedSession) {
					liveCloud.cancelActiveSession()
				}
				started
			}
		} finally {
			pendingStarts.decrementAndGet()
		}
	}

	suspend fun pauseRecording(onPaused: suspend () -> Unit = {}) {
		commandLock.withLock {
			if (recorder.recorderState.value == RecorderState.RECORDING) {
				onPaused()
				recorder.pauseRecording()
			}
		}
	}

	suspend fun resumeRecording(onResumed: suspend () -> Unit = {}) {
		commandLock.withLock {
			if (recorder.recorderState.value == RecorderState.PAUSED) {
				onResumed()
				recorder.resumeRecording()
			}
		}
	}

	suspend fun cancelRecording(onCancelled: suspend () -> Unit = {}) {
		commandLock.withLock {
			liveCloud.cancel()
			recorder.cancelRecording()
			onCancelled()
		}
	}

	suspend fun stopRecording(
		onRecordingSaved: suspend (recordingId: Long, timeBeforeSave: LocalTime) -> Unit = { _, _ -> },
		onSaveFailed: suspend (Throwable) -> Unit = {},
		onResetUi: suspend () -> Unit = {},
	) {
		var detachedCloud: DetachedCloudFinish? = null
		var savedRecordingId: Long? = null
		commandLock.withLock {
			transcriptLock.lock()
			var lockHeld = true
			try {
				val timeBeforeSave = recorder.recorderTimer.value
				recorder.stopRecording().fold(
					onSuccess = { recordingId ->
						savedRecordingId = recordingId
						// Detach this recording's live-cloud session while serialized with start/cancel
						detachedCloud = liveCloud.detachForFinish()
						onRecordingSaved(recordingId, timeBeforeSave)
						// Flush the tail utterance and persist the live on-device transcript
						try {
							saveLiveTranscript(recordingId)
						} finally {
							transcriptLock.unlock()
							lockHeld = false
						}
					},
					onFailure = { error ->
						liveCloud.cancelActiveSession()
						onSaveFailed(error)
					},
				)
			} finally {
				if (lockHeld) {
					transcriptLock.unlock()
					lockHeld = false
				}
			}
			onResetUi()
		}
		// Flush tail audio + final key points outside commandLock so a subsequent recording can start immediately
		val recordingId = savedRecordingId
		val cloudFinish = detachedCloud
		if (recordingId != null && cloudFinish != null) {
			runCatching { cloudFinish.finishAndSave(recordingId) }
				.onFailure { if (it is CancellationException) throw it else Log.e(TAG, "live cloud finish failed", it) }
		}
	}

	/** Saves the on-device live transcript as its own OFFLINE record (never overwrites cloud results). */
	private suspend fun saveLiveTranscript(recordingId: Long) {
		try {
			val live = transcriber.finishTranscription() ?: return
			val now = System.currentTimeMillis()
			transcriptStore.saveTranscript(
				TranscriptRecord(
					id = "offline-live-$now",
					recordingId = recordingId,
					source = TranscriptSource.OFFLINE,
					provenance = Provenance(
						engine = live.engine,
						model = live.modelId,
						trigger = "live",
						audioFormat = if (live.engine == "vosk") "streaming" else "分句辨識（VAD）",
					),
					createdAtMillis = now,
					status = if (live.complete) RunStatus.SUCCESS else RunStatus.PARTIAL,
					text = live.text,
					totalSegments = live.decodedSegments + live.failedSegments,
					completedSegments = live.decodedSegments,
					warnings = buildList {
						if (live.droppedMillis > 0) add("即時辨識跟不上，約 ${live.droppedMillis / 1000} 秒未辨識（可用離線重轉補齊）")
						if (live.failedSegments > 0) add("${live.failedSegments} 段辨識失敗")
					},
				),
			)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Log.e(TAG, "Failed to save live transcript", e)
		}
	}
}
