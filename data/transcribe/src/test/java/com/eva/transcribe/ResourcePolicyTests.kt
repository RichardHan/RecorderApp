package com.eva.transcribe

import com.eva.transcribe.resources.ResourcePolicy as P
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch

class ResourcePolicyTests {
    @Test fun storageIsLongAndBoundaryInclusive() {
        val download = 2_404_222_421L
        val need = P.downloadBytes(true, download, download, 0, false)
        assertEquals(download + 3_758_096_384L, need)
        assertTrue(need > Int.MAX_VALUE)
        assertEquals(false, P.diskEnough(need - 1, need))
        assertEquals(true, P.diskEnough(need, need))
        assertNull(P.diskEnough(null, need))
    }
    @Test fun installedHasNoAdditionalDownloadNeed() {
        assertEquals(0L, P.downloadBytes(true, 2404222421, 2404222421, 0, true))
    }
    @Test fun partialCountsOnlyExpectedFilesAndClampsBadSizes() {
        assertEquals(100L, P.partialBytes(100, 100, null))
        assertEquals(55L, P.partialBytes(100, null, 55))
        assertEquals(0L, P.partialBytes(100, null, 101))
        assertEquals(0L, P.partialBytes(100, 99, null))
        assertEquals(P.FILE_BUFFER + 45L, P.downloadBytes(true, 100, 100, 55, false))
    }
    @Test fun archiveIncludesScratchAndBuffer() {
        assertEquals(100L + 250L + P.ARCHIVE_BUFFER, P.archiveBytes(100, 250))
        assertEquals(300L + P.ARCHIVE_BUFFER, P.archiveBytes(100, null))
    }
    @Test fun ramLowStateAndArchitectureAndUnknown() {
        val weights = 2_399_761_248L
        val need = P.ramBytes(weights)
        assertEquals(true, P.ramEnough(need, false, need, true, weights))
        assertEquals(false, P.ramEnough(need - 1, false, need, true, weights))
        assertEquals(false, P.ramEnough(need, true, need, true, weights))
        assertEquals(false, P.ramEnough(need, false, need, false, weights))
        assertNull(P.ramEnough(need, false, null, true, null))
        assertNull(P.ramEnough(null, null, need, true, weights))
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResourcePollingTests {
    @Test fun lifecycleScopeCancellationStopsAndRestartRefreshesImmediately() = kotlinx.coroutines.test.runTest {
        var reads = 0
        fun start() = backgroundScope.launch {
            com.eva.transcribe.resources.pollResources(
                read = { reads++; com.eva.transcribe.resources.ResourceSnapshot() }, emit = {},
            )
        }
        val job = start()
        testScheduler.runCurrent()
        assertEquals(1, reads)
        testScheduler.advanceTimeBy(5000); testScheduler.runCurrent()
        assertEquals(2, reads)
        job.cancel(); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(30000); testScheduler.runCurrent()
        assertEquals(2, reads)
        val resumed = start(); testScheduler.runCurrent()
        assertEquals(3, reads)
        resumed.cancel()
    }
}
