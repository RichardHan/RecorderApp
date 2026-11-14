package com.eva.cloudtranscribe.model

import com.eva.cloudtranscribe.store.TranscriptStore
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEditingTests {

	private fun seg(i: Int, text: String, parts: List<SegmentResult> = emptyList()) =
		SegmentResult(i, i * 1000L, (i + 1) * 1000L, SegmentStatus.OK, text, parts = parts)

	private fun timed(updated: Long = 10L, status: RunStatus = RunStatus.SUCCESS) = TranscriptRecord(
		id = "run", recordingId = 7, source = TranscriptSource.OFFLINE, provenance = Provenance("offline", model = "m"),
		createdAtMillis = 1, updatedAtMillis = updated, status = status, text = "全文",
		segments = listOf(seg(0, "今天開會"), seg(1, "討論 Recoder 進度"), seg(2, "下週交付")),
	)

	private fun untimed() = TranscriptRecord(
		id = "cloud", recordingId = 7, source = TranscriptSource.CLOUD, provenance = Provenance("cloud", model = "m"),
		createdAtMillis = 1, updatedAtMillis = 5, status = RunStatus.SUCCESS, text = "第一行\n\n第二行 錯字\n第三行",
	)

	@Test fun editOneLineKeepsIdsAndTimesOfEveryLine() {
		val record = timed()
		val edits = TranscriptEditing.withLine(record, null, "s1", "討論 RecorderApp 進度", 100)!!
		val shown = TranscriptEditing.apply(record, edits)
		assertEquals(record.segments.map { Triple(it.index, it.startMs, it.endMs) }, shown.segments.map { Triple(it.index, it.startMs, it.endMs) })
		assertEquals(listOf("今天開會", "討論 RecorderApp 進度", "下週交付"), PlaybackTranscripts.lines(shown).map { it.text })
		assertEquals(listOf(0L, 1000L, 2000L), PlaybackTranscripts.lines(shown).map { it.startMs })
		assertEquals(1, edits.revision)
		assertEquals(record.id, shown.id)
	}

	@Test fun originalIsNeverRewrittenInTheStore() {
		val store = TranscriptStore(Files.createTempDirectory("edits").toFile())
		val record = timed()
		store.saveTranscript(record)
		store.saveEdits(TranscriptEditing.withLine(record, null, "s0", "今天的會議", 100)!!)
		assertEquals(record, store.transcript(7, "run"))
		assertEquals("今天的會議", store.transcriptsWithEdits(7).single().segments[0].text)
		store.deleteTranscript(7, "run")
		assertNull(store.edits(7, "run"))
	}

	@Test fun editsBumpUpdatedAtSoDependentChaptersCanTellTheTextChanged() {
		val record = timed()
		val edits = TranscriptEditing.withLine(record, null, "s0", "早安", 500)!!
		assertNotEquals(record.updatedAtMillis, TranscriptEditing.apply(record, edits).updatedAtMillis)
		assertSame(record, TranscriptEditing.apply(record, null))
	}

	@Test fun untimedTranscriptCanBeEditedWithoutInventingTimes() {
		val record = untimed()
		val keys = TranscriptEditing.editableLines(record)
		assertEquals(listOf("l0", "l1", "l2"), keys.map { it.key })
		assertTrue(keys.all { it.line.startMs == null && it.line.endMs == null })
		val edits = TranscriptEditing.withLine(record, null, "l1", "第二行 正確", 9)!!
		val shown = TranscriptEditing.apply(record, edits)
		assertEquals(listOf("第一行", "第二行 正確", "第三行"), PlaybackTranscripts.lines(shown).map { it.text })
		assertTrue(PlaybackTranscripts.lines(shown).all { it.startMs == null })
		assertTrue(shown.segments.isEmpty())
	}

	@Test fun splitPartsAreEditedIndividually() {
		val parts = listOf(SegmentResult(0, 500, 1000, SegmentStatus.OK, "後半"), SegmentResult(0, 0, 500, SegmentStatus.OK, "前半"))
		val record = timed().copy(segments = listOf(seg(0, "", parts)))
		assertEquals(listOf("s0.p0", "s0.p1"), TranscriptEditing.editableLines(record).map { it.key })
		val edits = TranscriptEditing.withLine(record, null, "s0.p0", "前面", 1)!!
		assertEquals(listOf("前面", "後半"), PlaybackTranscripts.lines(TranscriptEditing.apply(record, edits)).map { it.text })
	}

	@Test fun displayLinesMatchWhatPlaybackShowsForTheAppliedRecord() {
		listOf(timed(), untimed()).forEach { record ->
			val key = TranscriptEditing.editableLines(record)[1].key
			val edits = TranscriptEditing.withLine(record, null, key, "", 3)!!
			assertEquals(PlaybackTranscripts.lines(TranscriptEditing.apply(record, edits)), TranscriptEditing.displayLines(record, edits).map { it.line })
		}
	}

	@Test fun noChangeOrBlankingEverythingIsRejected() {
		val record = untimed()
		assertNull(TranscriptEditing.withLine(record, null, "l0", "第一行", 1))
		assertNull(TranscriptEditing.withLine(record, null, "missing", "x", 1))
		var edits = TranscriptEditing.withLine(record, null, "l0", " ", 1)!!
		edits = TranscriptEditing.withLine(record, edits, "l1", "", 2)!!
		assertNull(TranscriptEditing.withLine(record, edits, "l2", "", 3))
	}

	@Test fun revertingToOriginalDropsTheOverride() {
		val record = timed()
		val first = TranscriptEditing.withLine(record, null, "s2", "下週一交付", 1)!!
		val back = TranscriptEditing.withLine(record, first, "s2", "下週交付", 2)!!
		assertTrue(back.lines.isEmpty())
		assertEquals(2, back.revision)
	}

	@Test fun undoRestoresPreviousSaveOnce() {
		val record = timed()
		val one = TranscriptEditing.withLine(record, null, "s0", "A", 1)!!
		val two = TranscriptEditing.withLine(record, one, "s1", "B", 2)!!
		val undone = TranscriptEditing.undo(two, 3)!!
		assertEquals(mapOf("s0" to "A"), undone.lines)
		assertEquals(3, undone.revision)
		assertNull(TranscriptEditing.undo(undone, 4))
	}

	@Test fun changedOriginalIsNotSilentlyOverlaid() {
		val record = timed()
		val edits = TranscriptEditing.withLine(record, null, "s0", "A", 1)!!
		val newer = record.copy(updatedAtMillis = 99, segments = record.segments.take(1))
		assertFalse(TranscriptEditing.matches(newer, edits))
		assertSame(newer, TranscriptEditing.apply(newer, edits))
		val rebased = TranscriptEditing.rebase(newer, edits.copy(lines = edits.lines + ("s2" to "gone")), 5)
		assertTrue(TranscriptEditing.matches(newer, rebased))
		assertEquals(mapOf("s0" to "A"), rebased.lines)
	}

	@Test fun runningTranscriptsCannotBeEdited() {
		assertFalse(TranscriptEditing.canEdit(timed(status = RunStatus.RUNNING)))
		assertFalse(TranscriptEditing.canEdit(timed(status = RunStatus.QUEUED)))
		assertTrue(TranscriptEditing.canEdit(timed(status = RunStatus.PARTIAL)))
	}

	@Test fun replaceInLineIsLiteralAndCaseInsensitiveLikeSearch() {
		assertEquals("RecorderApp 和 RecorderApp", TranscriptEditing.replaceInLine("recoder 和 Recoder", "recoder", "RecorderApp"))
		assertEquals("不變", TranscriptEditing.replaceInLine("不變", "x", "y"))
		assertEquals("a.b", TranscriptEditing.replaceInLine("a*b", "*", "."))
	}

	@Test fun editsSurviveStoreRoundTripAndInvalidateSearchSignature() {
		val dir = Files.createTempDirectory("edits-sig").toFile()
		val store = TranscriptStore(dir)
		val record = timed()
		store.saveTranscript(record)
		val before = store.transcriptsSignature(7)
		Thread.sleep(1100) // file mtime granularity on some file systems is one second
		val edits = TranscriptEditing.withLine(record, null, "s0", "A", 1)!!
		store.saveEdits(edits)
		assertEquals(edits, store.edits(7, "run"))
		assertNotNull(store.edits(7, "run"))
		assertTrue(store.transcriptsSignature(7) > before)
	}
}
