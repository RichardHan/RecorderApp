package com.eva.feature_recordings.search.transcript

import com.eva.cloudtranscribe.model.PlaybackLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun rec(id: Long, name: String) = TranscriptSearchRecording(id, name)
private fun line(text: String, startMs: Long? = null) = PlaybackLine(text, startMs, startMs?.plus(1_000))

class TranscriptSearcherTests {

	private val timed = listOf(line("下週三交 API 報價", 0), line("好", 1_000))

	@Test
	fun matchesSeveralRecordingsAndKeepsTheirOrder() = runTest {
		val result = TranscriptSearcher().search("api", listOf(rec(1, "One"), rec(2, "Two"))) { id ->
			when (id) {
				1L -> timed
				2L -> listOf(line("api latency 太高", 5_000))
				else -> null
			}
		}
		assertEquals(listOf("One", "Two"), result.hits.map { it.recordingName })
		assertEquals(listOf(1L, 2L), result.hits.map { it.recordingId })
		assertEquals(listOf(0L, 5_000L), result.hits.map { it.startMs })
		assertEquals(listOf("下週三交 API 報價", "api latency 太高"), result.hits.map { it.text })
		assertFalse(result.truncated)
	}

	@Test
	fun blankQueryReturnsNothingAndNeverTouchesTheStore() = runTest {
		var loads = 0
		val result = TranscriptSearcher().search("  \n ", listOf(rec(1, "One"))) { loads++; timed }
		assertTrue(result.hits.isEmpty())
		assertFalse(result.truncated)
		assertEquals(0, loads)
	}

	@Test
	fun untimedTranscriptReportsNoTime() = runTest {
		val untimed = listOf(PlaybackLine("下週三交 API 報價"))
		val result = TranscriptSearcher().search("api", listOf(rec(1, "One"))) { untimed }
		assertEquals(1, result.hits.size)
		assertNull(result.hits.first().startMs)
	}

	@Test
	fun recordingsWithoutUsableTranscriptAreSkipped() = runTest {
		val result = TranscriptSearcher().search("api", listOf(rec(1, "Empty"), rec(2, "Full"))) { id ->
			if (id == 2L) timed else null
		}
		assertEquals(listOf("Full"), result.hits.map { it.recordingName })
	}

	@Test
	fun resultCapMarksTheListAsTruncated() = runTest {
		val many = (1..5).map { line("api $it", it * 1_000L) }
		val result = TranscriptSearcher(maxHits = 2).search("api", listOf(rec(1, "One"))) { many }
		assertEquals(2, result.hits.size)
		assertTrue(result.truncated)
	}
}

class TranscriptLineCacheTests {

	@Test
	fun sameSignatureLoadsOnceAndChangedSignatureReloads() {
		var signature = 10L
		var loads = 0
		val cache = TranscriptLineCache(signature = { signature }, load = { loads++; listOf(line("a")) })

		assertEquals(1, cache.lines(1)?.size)
		assertEquals(1, cache.lines(1)?.size)
		assertEquals(1, loads)

		signature = 11L
		cache.lines(1)
		assertEquals(2, loads)

		cache.clear()
		cache.lines(1)
		assertEquals(3, loads)
	}

	@Test
	fun missingTranscriptIsCachedTooAndOnlyReloadedWhenTheSignatureMoves() {
		var signature = 0L
		var loads = 0
		val cache = TranscriptLineCache(signature = { signature }, load = { loads++; null })

		assertNull(cache.lines(7))
		assertNull(cache.lines(7))
		assertEquals(1, loads)

		signature = 42L
		assertNull(cache.lines(7))
		assertEquals(2, loads)
	}
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TranscriptSearchFlowTests {

	private val recordings = listOf(rec(1, "One"))

	@Test
	fun debouncesKeystrokesAndOnlySearchesTheNewestQuery() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val queries = MutableSharedFlow<String>(extraBufferCapacity = 8)
		var loads = 0
		val results = mutableListOf<TranscriptSearchResult>()

		val job = launch {
			queries.transcriptSearch(
				searcher = TranscriptSearcher(),
				recordings = { recordings },
				lines = { loads++; listOf(line("alpha")) },
				dispatcher = dispatcher,
			).collect { results += it }
		}

		queries.emit("a")
		advanceTimeBy(100)
		queries.emit("al")
		advanceTimeBy(100)
		queries.emit("alp")
		advanceUntilIdle()

		assertEquals("only the last keystroke may run a search", 1, loads)
		assertEquals(1, results.size)
		assertEquals(1, results.first().hits.size)
		job.cancel()
	}

	@Test
	fun newerQueryCancelsTheSearchThatIsStillRunning() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val queries = MutableSharedFlow<String>(extraBufferCapacity = 8)
		var cancelled = false
		var loads = 0
		val results = mutableListOf<TranscriptSearchResult>()

		val job = launch {
			queries.transcriptSearch(
				searcher = TranscriptSearcher(),
				recordings = { recordings },
				lines = { _ ->
					loads++
					if (loads == 1) {
						try {
							delay(10_000)
							listOf(line("slow"))
						} catch (e: CancellationException) {
							cancelled = true
							throw e
						}
					} else listOf(line("fast"))
				},
				dispatcher = dispatcher,
			).collect { results += it }
		}

		queries.emit("slow")
		advanceTimeBy(400)
		runCurrent()
		queries.emit("fast")
		advanceUntilIdle()

		assertTrue("the running search must be cancelled", cancelled)
		assertEquals(1, results.size)
		assertEquals("fast", results.first().hits.single().text)
		job.cancel()
	}

	@Test
	fun clearingTheQueryClearsTheResults() = runTest {
		val dispatcher = StandardTestDispatcher(testScheduler)
		val queries = MutableSharedFlow<String>(extraBufferCapacity = 8)
		val results = mutableListOf<TranscriptSearchResult>()

		val job = launch {
			queries.transcriptSearch(
				searcher = TranscriptSearcher(),
				recordings = { recordings },
				lines = { listOf(line("alpha")) },
				dispatcher = dispatcher,
			).collect { results += it }
		}

		queries.emit("alp")
		advanceUntilIdle()
		assertEquals(1, results.last().hits.size)

		queries.emit("")
		advanceUntilIdle()
		assertTrue(results.last().hits.isEmpty())
		job.cancel()
	}
}
