package io.github.richardhan.voicenote

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptSearchTest {
	@get:Rule val temp = TemporaryFolder()

	@Test fun matchesWordsWithinCjkSentencesAndLiteralEnglishPunctuation() {
		val text = "核准年度預算。批准年度预算。見積書を確認。 BUDGET [v2.1] C++."
		for (query in listOf("預算", "预算", "見積", "budget", "[v2.1]", "C++")) {
			assertNotNull(query, findExcerpt(text, query))
		}
		assertNull(findExcerpt(text, ".*"))
		assertNull(findExcerpt(text, " "))
	}

	@Test fun excerptUsesFirstMatchInLongTranscript() {
		val text = "x".repeat(20_000) + "Target first line\nsecond line" + "y".repeat(200) + "Target again"
		val snippet = requireNotNull(findExcerpt(text, "target"))
		assertTrue(snippet.startsWith("…"))
		assertTrue(snippet.endsWith("…"))
		assertTrue(snippet.contains("Target first line second line"))
		assertFalse(snippet.contains("again"))
	}

	@Test fun readsCurrentSidecarAndHandlesDeletion() {
		val recording = temp.newFile("meeting.m4a")
		val sidecar = File(recording.parentFile, "meeting.txt")
		assertEquals("", readTranscript(recording))
		sidecar.writeText("old transcript")
		assertNotNull(findExcerpt(readTranscript(recording), "old"))
		sidecar.writeText("更新された内容")
		assertNull(findExcerpt(readTranscript(recording), "old"))
		assertNotNull(findExcerpt(readTranscript(recording), "内容"))
		assertTrue(sidecar.delete())
		assertEquals("", readTranscript(recording))
	}

	@Test fun findsEveryNonOverlappingLiteralOccurrenceInReadingOrder() {
		val text = "預算 ｜ BUDGET [v2.1] 🗓️ " + "段落".repeat(800) + "預算 budget [v2.1] 見積見積"
		for (word in listOf("預算", "budget", "[v2.1]", "見積")) {
			assertEquals(listOf(text.indexOf(word, ignoreCase = true), text.lastIndexOf(word, ignoreCase = true)),
				transcriptMatchOffsets(text, word))
		}
		assertEquals(listOf(0, 2), transcriptMatchOffsets("aaaaa", "aa"))
		assertTrue(transcriptMatchOffsets(text, " ").isEmpty())
		assertTrue(transcriptMatchOffsets(text, "absent").isEmpty())
	}
}
