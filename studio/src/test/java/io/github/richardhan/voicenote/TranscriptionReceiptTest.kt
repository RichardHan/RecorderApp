package io.github.richardhan.voicenote

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptionReceiptTest {
	@get:Rule val folder = TemporaryFolder()

	@Test fun amountPreservesFreeAndSubCentCharges() {
		assertEquals("0", reportedCostUsd("0.000"))
		assertEquals("0.00000013", reportedCostUsd("1.3e-7"))
		for (value in listOf(null, "", "null", "NaN", "-1", "Infinity", "1e99999999")) {
			assertNull(reportedCostUsd(value))
		}
	}

	@Test fun legacyAndExternallyReplacedTextStillOpen() {
		val file = folder.newFile("meeting.m4a")
		assertEquals(SavedTranscript(""), loadSavedTranscript(file))
		transcriptFile(file).writeText("前回の会議")
		assertEquals(SavedTranscript("前回の会議"), loadSavedTranscript(file))
		saveTranscription(file, "會議一", TranscriptionReceipt("test", 20, "1"))
		transcriptFile(file).writeText("會議二")
		assertEquals(SavedTranscript("會議二"), loadSavedTranscript(file))
	}

	@Test fun sameTranscriptGetsTheLatestReceiptIncludingAnUnreportedCost() {
		val file = folder.newFile("meeting.m4a")
		val text = "日本語、中文、English\nSecond line"
		for (receipt in listOf(
			TranscriptionReceipt("first", 91, "0.12345"),
			TranscriptionReceipt("second", 762, "0"),
			TranscriptionReceipt("third", 123, null)
		)) {
			saveTranscription(file, text, receipt)
			assertEquals(SavedTranscript(text, receipt), loadSavedTranscript(file))
			assertEquals(text, transcriptFile(file).readText())
		}
	}

	@Test fun failedTranscriptionKeepsThePreviousResult() {
		val file = folder.newFile("meeting.m4a")
		val previous = TranscriptionReceipt("model", 203, "0.011")
		saveTranscription(file, "原文", previous)
		assertThrows(IllegalArgumentException::class.java) { saveTranscription(file, " ", TranscriptionReceipt("retry", 1, null)) }
		assertEquals(SavedTranscript("原文", previous), loadSavedTranscript(file))
		File(folder.root, file.name + ".usage").writeText("model=broken")
		assertEquals(SavedTranscript("原文"), loadSavedTranscript(file))
	}
}
