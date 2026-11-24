package com.eva.player.data.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.time.Duration.Companion.seconds
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
}
