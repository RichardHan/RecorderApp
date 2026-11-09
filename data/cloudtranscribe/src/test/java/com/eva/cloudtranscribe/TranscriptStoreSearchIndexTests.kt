package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.model.Provenance
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.model.TranscriptSource
import com.eva.cloudtranscribe.store.TranscriptStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only index helpers the transcript search needs: which recordings have data, and when it last changed. */
class TranscriptStoreSearchIndexTests {

	private fun root(): File = Files.createTempDirectory("transcript-store-index").toFile()

	private fun storeWith(vararg recordingIds: Long): TranscriptStore = TranscriptStore(root()).also { store ->
		recordingIds.forEach { id -> store.saveTranscript(record(id)) }
	}

	private fun record(recordingId: Long, id: String = "run-$recordingId") = TranscriptRecord(
		id = id,
		recordingId = recordingId,
		source = TranscriptSource.CLOUD,
		provenance = Provenance(engine = "cloud", model = "m"),
		createdAtMillis = 1_000,
		status = RunStatus.SUCCESS,
		text = "逐字稿 $recordingId",
	)

	@Test
	fun emptyStoreHasNoRecordingsAndNoSignature() {
		val store = TranscriptStore(root())
		assertTrue(store.recordingIds().isEmpty())
		assertEquals(0L, store.transcriptsSignature(1))
	}

	@Test
	fun recordingIdsListsEveryRecordingThatHasTranscriptData() {
		val store = storeWith(1, 2)
		assertEquals(setOf(1L, 2L), store.recordingIds().toSet())
	}

	@Test
	fun signatureIsZeroWithoutTranscriptsAndFollowsTheNewestFile() {
		val store = storeWith(1)
		val first = store.transcriptsSignature(1)
		assertTrue("a saved transcript must produce a non-zero signature", first > 0L)
		assertEquals(0L, store.transcriptsSignature(99))

		store.saveTranscript(record(1, id = "run-1-second"))
		assertTrue(store.transcriptsSignature(1) >= first)

		store.deleteTranscript(1, "run-1")
		store.deleteTranscript(1, "run-1-second")
		assertEquals(0L, store.transcriptsSignature(1))
	}

	@Test
	fun unparsableTranscriptFileDoesNotHideItsRecording() {
		val store = storeWith(1)
		val dir = File(store.checkpointDir(1, "x").parentFile, "transcripts")
		File(dir, "broken.json").writeText("{ not json")
		assertTrue(store.recordingIds().contains(1))
		assertTrue(store.transcriptsSignature(1) > 0L)
	}
}
