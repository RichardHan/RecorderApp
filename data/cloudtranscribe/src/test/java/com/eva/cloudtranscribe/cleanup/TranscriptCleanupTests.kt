package com.eva.cloudtranscribe.cleanup

import com.eva.cloudtranscribe.model.PlaybackLine
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCleanupTests {
	private val batch = listOf(
		CleanupSentence(0, 0, "嗯 那個 我們今天 今天要討論上線"),
		CleanupSentence(1, 1000, "呃"),
		CleanupSentence(2, 2000, "然後下週三交付"),
	)

	@Test fun sentencesKeepDisplayIndexAndOriginalTimeAndSkipBlanks() {
		val s = TranscriptCleanup.sentences(listOf(PlaybackLine("a", 5, 9), PlaybackLine("  "), PlaybackLine("b")))
		assertEquals(listOf(CleanupSentence(0, 5, "a"), CleanupSentence(2, null, "b")), s)
	}

	@Test fun validAnswerIsAcceptedEvenInsideCodeFence() {
		val answer = "```json\n[{\"id\":0,\"text\":\"我們今天要討論上線。\"},{\"id\":1,\"text\":\"\"},{\"id\":2,\"text\":\"下週三交付。\"}]\n```"
		val parsed = TranscriptCleanup.parse(answer, batch, CleanupIntensity.LOW)!!
		assertEquals(listOf(0, 1, 2), parsed.map { it.id })
		assertEquals("", parsed[1].text)
	}

	@Test fun extraMissingReorderedOrUnknownIdsRejectTheWholeBatch() {
		assertNull(TranscriptCleanup.parse("""[{"id":0,"text":"a"},{"id":2,"text":"b"}]""", batch, CleanupIntensity.LOW))
		assertNull(TranscriptCleanup.parse("""[{"id":0,"text":"a"},{"id":1,"text":""},{"id":2,"text":"b"},{"id":3,"text":"c"}]""", batch, CleanupIntensity.LOW))
		assertNull(TranscriptCleanup.parse("""[{"id":0,"text":"a"},{"id":2,"text":"b"},{"id":1,"text":""}]""", batch, CleanupIntensity.LOW))
		assertNull(TranscriptCleanup.parse("""[{"id":0,"text":"a"},{"id":1,"text":""},{"id":9,"text":"b"}]""", batch, CleanupIntensity.LOW))
		assertNull(TranscriptCleanup.parse("""[{"id":0,"text":1},{"id":1,"text":""},{"id":2,"text":"b"}]""", batch, CleanupIntensity.LOW))
		assertNull(TranscriptCleanup.parse("沒有 JSON", batch, CleanupIntensity.LOW))
	}

	@Test fun answersThatAddContentAreRejected() {
		val invented = "然後下週三交付，負責人是王小明，預算三百萬元，並且要同步通知所有部門主管"
		assertNull(TranscriptCleanup.parse("""[{"id":0,"text":"a"},{"id":1,"text":""},{"id":2,"text":"$invented"}]""", batch, CleanupIntensity.LOW))
		assertNull(TranscriptCleanup.parse("""[{"id":0,"text":""},{"id":1,"text":""},{"id":2,"text":""}]""", batch, CleanupIntensity.LOW))
	}

	@Test fun mediumMayMergeIntoPreviousButNotGrowOverall() {
		val merged = """[{"id":0,"text":"我們今天要討論上線，下週三交付。"},{"id":1,"text":""},{"id":2,"text":""}]"""
		assertNotNull(TranscriptCleanup.parse(merged, batch, CleanupIntensity.MEDIUM))
		val grown = "x".repeat(200)
		assertNull(TranscriptCleanup.parse("""[{"id":0,"text":"$grown"},{"id":1,"text":""},{"id":2,"text":""}]""", batch, CleanupIntensity.MEDIUM))
	}

	@Test fun promptCarriesHardRulesAndNumberedSentencesOnly() {
		val p = TranscriptCleanup.prompt(batch, CleanupIntensity.LOW)
		assertTrue(p.contains("[0] 嗯 那個"))
		assertTrue(p.contains("不得新增、推測或補完"))
		assertTrue(p.contains("不要加說話者或時間"))
		assertTrue(!p.contains("1000"))
	}

	@Test fun batchesKeepOrderAndSize() {
		val many = (0 until 10).map { CleanupSentence(it, null, "x".repeat(400)) }
		val b = TranscriptCleanup.batches(many, maxChars = 1000)
		assertEquals((0 until 10).toList(), b.flatten().map { it.id })
		assertTrue(b.all { it.sumOf { s -> s.text.length } <= 1000 })
		assertEquals(1, TranscriptCleanup.batches(listOf(CleanupSentence(0, null, "y".repeat(5000))), 1000).size)
	}

	@Test fun storeRoundTripsAndNeverTouchesOtherFiles() {
		val root = Files.createTempDirectory("cleanup").toFile()
		val store = TranscriptCleanupStore(root)
		val r = CleanupRecord(4, "t", "t@1", CleanupIntensity.LOW, "m", 2, listOf(CleanedSentence(0, "好")))
		store.save(r)
		assertEquals(r, store.cleanup(4, CleanupIntensity.LOW))
		assertNull(store.cleanup(4, CleanupIntensity.MEDIUM))
		assertEquals(listOf("cleanups"), root.resolve("v1/4").list()!!.toList())
		store.delete(4, CleanupIntensity.LOW)
		assertNull(store.cleanup(4, CleanupIntensity.LOW))
		assertEquals("好", TranscriptCleanup.plainText(r))
	}
}
