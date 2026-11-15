package com.eva.recordings.photos

import com.eva.recordings.domain.models.PhotoRules
import org.junit.Assert.*
import org.junit.Test

class PhotoRulesTests {
	@Test fun resizePreservesAspectRatioAndNeverUpscales() {
		assertEquals(1600 to 1200, PhotoRules.dimensions(4000, 3000))
		assertEquals(800 to 1600, PhotoRules.dimensions(2000, 4000))
		assertEquals(50 to 100, PhotoRules.dimensions(50, 100))
	}
	@Test fun photosMatchRealClosestTimestampAndUntimedTextUsesFallback() {
		assertEquals(1, PhotoRules.nearestLine(4700, listOf(0L, 4000L, 9000L)))
		assertEquals(0, PhotoRules.nearestLine(0, listOf(2000L, 4000L)))
		assertNull(PhotoRules.nearestLine(4700, listOf(null, null)))
	}
}
