package com.eva.cloudtranscribe.model

import com.eva.cloudtranscribe.store.TranscriptStore
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class SummaryClaimTests {
	private fun candidate(id: String, format: String = "CONCISE") = SummaryRecord(
		id, 1L, "transcript", Provenance("cloud", model = "m", summaryFormat = format), 1L, RunStatus.QUEUED)

	@Test fun concurrentManualAndAutomaticRequestsReserveOnlyOneJob() {
		val root = Files.createTempDirectory("summary-claim").toFile()
		val store = TranscriptStore(root)
		val executor = Executors.newFixedThreadPool(8)
		try {
			val ids = executor.invokeAll((1..32).map { i -> Callable {
				store.claimSummary(candidate("s$i"), automatic = i % 2 == 0).id
			} }).map { it.get() }
			assertEquals(1, ids.distinct().size)
			assertEquals(1, store.summaries(1).size)
			assertEquals(ids.first(), TranscriptStore(root).claimSummary(candidate("restart"), true).id)
		} finally { executor.shutdownNow(); root.deleteRecursively() }
	}

	@Test fun manualDifferentFormatAndFinishedRetryRemainAvailable() {
		val root = Files.createTempDirectory("summary-retry").toFile()
		try {
			val store = TranscriptStore(root)
			val first = store.claimSummary(candidate("first"), false)
			assertEquals("different", store.claimSummary(candidate("different", "MEETING"), false).id)
			store.saveSummary(first.copy(status = RunStatus.FAILED))
			assertEquals("retry", store.claimSummary(candidate("retry"), false).id)
			assertNotEquals("auto", store.claimSummary(candidate("auto"), true).id)
		} finally { root.deleteRecursively() }
	}
}
