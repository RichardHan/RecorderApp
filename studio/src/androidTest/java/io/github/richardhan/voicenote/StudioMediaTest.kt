package io.github.richardhan.voicenote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class StudioMediaTest {
	private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StudioApplication
	@get:Rule val folder = TemporaryFolder(app.cacheDir)

	@Test fun cutRemovesOnlySelectedSamplesAndLeavesSourceIntact() = runBlocking {
		val samples = FloatArray(3 * 16000) { if (it < 16000) 0.25f else if (it < 32000) 0.75f else -0.25f }
		val file = folder.newFile("original.wav")
		val original = pcmWave(samples)
		file.writeBytes(original)
		val spans = selectClip(listOf(ClipSpan(0, 3000)), ClipSpan(1000, 2000), true)
		assertEquals(listOf(ClipSpan(0, 1000), ClipSpan(2000, 3000)), spans)
		val edited = saveClip(file, spans)
		assertEquals("wav", edited.extension)
		assertEquals(44L + 64000L, edited.length())
		val result = decodeForSpeakers(edited)
		assertEquals(0.25f, result[8000], 0.001f)
		assertEquals(-0.25f, result[24000], 0.001f)
		assertArrayEquals(original, file.readBytes())
	}

	@Test fun importedSubtitlesRetainTheirTimesAndHaveNoProviderReceipt() {
		val file = folder.newFile("recording.m4a")
		val vtt = "WEBVTT\n\n1\n00:00:01.250 --> 00:00:02.500\nA &amp; B\n"
		val (text, segments) = importedTranscript(vtt)
		saveTranscription(file, text, null, segments)
		assertEquals("A & B", text)
		assertNull(loadSavedTranscript(file).receipt)
		assertEquals(listOf(TimedText(1.25, 2.5, text)), readTiming(file))
	}

	@Test fun photosAreResizedAndPrivateHistorySurvivesUntilPermanentDeletion() {
		val directory = File(app.filesDir, "audio").apply { mkdirs() }
		val audio = File.createTempFile("photo-", ".wav", directory)
		val source = folder.newFile("original.png")
		val bitmap = Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888)
		source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
		bitmap.recycle()
		try {
			val picture = copyPhoto(app, Uri.fromFile(source), audio, 1234)
			val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(picture.path, options)
			assertEquals(1600, options.outWidth)
			assertEquals(1200, options.outHeight)
			assertTrue(picture.name.startsWith("000000001234-"))
			val receipt = TranscriptionReceipt("test", 100, "0")
			saveTranscription(audio, "first version", receipt)
			saveTranscription(audio, "second version", receipt)
			val history = File(directory, audio.name + ".history").listFiles()!!.single()
			assertEquals("first version", File(history, "text.txt").readText())
			val zip = ByteArrayOutputStream().also { exportTranscripts(it, listOf(audio)) }
			ZipInputStream(zip.toByteArray().inputStream()).use {
				assertEquals(audio.name + ".txt", it.nextEntry.name)
				assertEquals("second version", it.readBytes().decodeToString())
			}
			app.library.refresh()
			app.library.trash(audio)
			assertTrue(picture.exists())
			assertTrue(app.library.purge(audio))
			assertFalse(picture.exists())
			assertFalse(history.exists())
			assertFalse(transcriptFile(audio).exists())
			assertTrue(source.exists())
		} finally {
			removeRecording(app, audio)
			app.library.refresh()
		}
	}
}
