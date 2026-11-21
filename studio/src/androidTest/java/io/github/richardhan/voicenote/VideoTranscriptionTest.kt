package io.github.richardhan.voicenote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoTranscriptionTest {
	@get:Rule val folder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

	@Test fun completedVideoStoresSpokenTextAndVideoModelCharge() {
		val video = folder.newFile("meeting.youtube")
		val response = JSONObject("""{"status":"completed","usage":{"cost":0.0027},"output":[
			{"type":"reasoning"}, {"content":[{"type":"output_text","text":"Hello."}]},
			{"content":[{"type":"output_text","text":"Goodbye."}]}]}""")
		saveVideoTranscription(video, response, "video/model", 321)
		assertEquals(SavedTranscript("Hello.\nGoodbye.", TranscriptionReceipt("video/model", 321, "0.0027")), loadSavedTranscript(video))
	}

	@Test fun incompleteVideoDoesNotReplaceThePreviousTranscriptOrReceipt() {
		val video = folder.newFile("interview.youtube")
		val receipt = TranscriptionReceipt("prior/model", 42, "0")
		saveTranscription(video, "Previously saved", receipt)
		val response = JSONObject("""{"status":"incomplete","output":[{"content":[{"type":"output_text","text":"Partial"}]}]}""")
		assertThrows(IllegalArgumentException::class.java) { saveVideoTranscription(video, response, "new/model", 123) }
		assertEquals(SavedTranscript("Previously saved", receipt), loadSavedTranscript(video))
	}
}
