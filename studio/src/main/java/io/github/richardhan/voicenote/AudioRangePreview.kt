package io.github.richardhan.voicenote

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

@Composable
internal fun AudioRangePreview(file: File, startMs: Long, endMs: Long, onStop: () -> Unit, onError: () -> Unit) {
	val stop by rememberUpdatedState(onStop)
	val reportError by rememberUpdatedState(onError)
	LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stop() }
	LaunchedEffect(file, startMs, endMs) {
		val player = MediaPlayer()
		val prepared = CompletableDeferred<Unit>()
		val positioned = CompletableDeferred<Unit>()
		val completed = CompletableDeferred<Unit>()
		try {
			require(startMs >= 0 && endMs > startMs)
			player.setOnPreparedListener { prepared.complete(Unit) }
			player.setOnSeekCompleteListener { positioned.complete(Unit) }
			player.setOnCompletionListener { completed.complete(Unit) }
			player.setOnErrorListener { _, what, extra ->
				val error = IOException("MediaPlayer error ($what, $extra)")
				prepared.completeExceptionally(error)
				positioned.completeExceptionally(error)
				completed.completeExceptionally(error)
				true
			}
			player.setDataSource(file.path)
			player.prepareAsync()
			prepared.await()
			require(startMs < player.duration)
			if (startMs > 0) {
				player.seekTo(startMs, MediaPlayer.SEEK_CLOSEST)
				positioned.await()
			}
			player.start()
			while (!completed.isCompleted && player.currentPosition < endMs) delay(20)
			if (completed.isCompleted) completed.await()
			stop()
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			reportError()
		} finally {
			player.release()
		}
	}
}
