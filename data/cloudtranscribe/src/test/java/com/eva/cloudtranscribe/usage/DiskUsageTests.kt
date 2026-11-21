package com.eva.cloudtranscribe.usage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskUsageTests {
	@get:Rule val tmp = TemporaryFolder()

	@Test
	fun directoryBytes_sumsNestedFilesAndIgnoresMissing() {
		val root = tmp.newFolder("sidecars")
		File(root, "a.txt").writeBytes(ByteArray(100))
		val nested = File(root, "nested").apply { mkdirs() }
		File(nested, "b.txt").writeBytes(ByteArray(50))
		assertEquals(150L, DiskUsage.directoryBytes(root))
		assertEquals(0L, DiskUsage.directoryBytes(File(root, "missing")))
		assertEquals(150L, DiskUsage.totalBytes(listOf(150L, -3L, 0L)))
	}

	@Test
	fun formatBytes_usesKbMbGb() {
		assertEquals("0 MB", DiskUsage.formatBytes(0))
		assertEquals("1 KB", DiskUsage.formatBytes(100))
		assertEquals("2 MB", DiskUsage.formatBytes(2L * 1024 * 1024))
		assertEquals("1.5 GB", DiskUsage.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
	}
}
