package io.github.richardhan.voicenote

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmrImportTest {
	private val context = InstrumentationRegistry.getInstrumentation().targetContext
	@get:Rule val folder = TemporaryFolder(context.cacheDir)

	@Test fun importsNarrowbandRecording() {
		checkImport("#!AMR\n", 12, "amr", MediaFormat.MIMETYPE_AUDIO_AMR_NB)
	}

	@Test fun importsWidebandRecording() {
		checkImport("#!AMR-WB\n", 17, "awb", MediaFormat.MIMETYPE_AUDIO_AMR_WB)
	}

	private fun checkImport(header: String, payloadSize: Int, extension: String, codec: String) {
		val source = folder.newFile("shared-audio")
		source.outputStream().use { output ->
			output.write(header.toByteArray(Charsets.US_ASCII))
			// One second of mode 0 speech frames, each with a good-quality frame header.
			repeat(50) {
				output.write(0x04)
				output.write(ByteArray(payloadSize))
			}
		}
		val library = folder.newFolder("audio")
		importDocument(context, library, Uri.fromFile(source))

		val imported = library.listFiles()!!.single()
		assertEquals(extension, imported.extension)
		assertArrayEquals(source.readBytes(), imported.readBytes())
		val extractor = MediaExtractor()
		try {
			extractor.setDataSource(imported.path)
			assertEquals(codec, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
		} finally {
			extractor.release()
		}
	}
}
