package io.github.richardhan.voicenote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimedTranscriptionTest {
	@get:Rule val folder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

	@Test fun untimedRetryClearsOldAlignmentEvenWhenTheWordsAreIdentical() {
		val audio = folder.newFile("meeting.m4a")
		val text = "Next week."
		val segments = JSONArray("""[{"start":2,"end":3.5,"text":"Next week."}]""")
		val timed = TranscriptionReceipt("timed-model", 745, "0.0012")
		saveTranscription(audio, text, timed, segments)
		val untimed = TranscriptionReceipt("plain-model", 503, null)
		assertThrows(IllegalArgumentException::class.java) { saveTranscription(audio, " ", untimed) }
		assertEquals(SavedTranscript(text, timed), loadSavedTranscript(audio))
		assertEquals(listOf(TimedText(2.0, 3.5, text)), readTiming(audio))

		saveTranscription(audio, text, untimed)
		assertEquals(SavedTranscript(text, untimed), loadSavedTranscript(audio))
		assertEquals(emptyList<TimedText>(), readTiming(audio))
	}
}
