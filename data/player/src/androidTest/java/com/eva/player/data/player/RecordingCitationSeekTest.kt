package com.eva.player.data.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class RecordingCitationSeekTest {
	@Test
	fun citation_waits_for_its_media_item_to_become_seekable() = runBlocking {
		withContext(Dispatchers.Main) {
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			val player = ExoPlayer.Builder(context).build()
			try {
				val wrapper = AudioFilePlayerImpl(player)
				val seek = launch(start = CoroutineStart.UNDISPATCHED) { wrapper.seekToRecording(42, 5.seconds) }
				assertFalse(seek.isCompleted)
				val source = SilenceMediaSource(12_000_000L)
				source.updateMediaItem(source.mediaItem.buildUpon().setMediaId("42").build())
				player.setMediaSource(source)
				player.prepare()
				withTimeout(10_000) { seek.join() }
				assertEquals(5_000L, player.currentPosition)
			} finally {
				player.release()
			}
		}
	}

	@Test
	fun citation_seeks_after_playback_reaches_ended_and_stays_paused() = runBlocking {
		withContext(Dispatchers.Main) {
			val context = InstrumentationRegistry.getInstrumentation().targetContext
			val player = ExoPlayer.Builder(context).build()
			try {
				val ended = CompletableDeferred<Unit>()
				player.addListener(object : Player.Listener {
					override fun onPlaybackStateChanged(playbackState: Int) {
						if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
					}
				})
				val source = SilenceMediaSource(12_000_000L)
				source.updateMediaItem(source.mediaItem.buildUpon().setMediaId("42").build())
				player.setMediaSource(source)
				player.prepare()
				player.seekTo(11_900L)
				player.play()
				withTimeout(10_000) { ended.await() }
				assertEquals(Player.STATE_ENDED, player.playbackState)

				val readyAgain = CompletableDeferred<Unit>()
				player.addListener(object : Player.Listener {
					override fun onPlaybackStateChanged(playbackState: Int) {
						if (playbackState == Player.STATE_READY) readyAgain.complete(Unit)
					}
				})
				val wrapper = AudioFilePlayerImpl(player)
				withTimeout(10_000) { wrapper.seekToRecording(42, 5.seconds) }
				withTimeout(10_000) { readyAgain.await() }
				assertEquals(5_000L, player.currentPosition)
				assertFalse(player.playWhenReady)
				assertFalse(player.isPlaying)
			} finally {
				player.release()
			}
		}
	}
}
