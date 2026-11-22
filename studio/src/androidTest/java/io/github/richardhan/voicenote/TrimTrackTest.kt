package io.github.richardhan.voicenote

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrimTrackTest {
	@get:Rule val folder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

	@Test fun m4aWithDurationButNoAacIsNotTrimmable() {
		val file = folder.newFile("voice.m4a")
		val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
		try {
			val track = muxer.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 1))
			val noDataFrame = ByteBuffer.wrap(byteArrayOf(0x7c))
			val info = MediaCodec.BufferInfo()
			muxer.start()
			repeat(20) { i ->
				info.set(0, 1, i * 20_000L, 0)
				muxer.writeSampleData(track, noDataFrame, info)
			}
			muxer.stop()
		} finally { muxer.release() }

		val metadata = MediaMetadataRetriever()
		try {
			metadata.setDataSource(file.path)
			assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
		} finally { metadata.release() }
		assertNull(aacTrackDurationUs(file))
	}
}
