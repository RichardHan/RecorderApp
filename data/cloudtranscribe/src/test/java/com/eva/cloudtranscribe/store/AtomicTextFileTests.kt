package com.eva.cloudtranscribe.store

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicTextFileTests {
	@get:Rule val folder = TemporaryFolder()
	@Test fun `writes and replaces UTF8 content without leftover temp files`() {
		val target = File(folder.root, "nested/record.json")
		AtomicTextFile.write(target, "原稿 😀")
		assertEquals("原稿 😀", target.readText())
		AtomicTextFile.write(target, "新版逐字稿")
		assertEquals("新版逐字稿", target.readText())
		assertEquals(listOf("record.json"), target.parentFile!!.list()!!.toList())
	}
	@Test fun `replacement failure preserves previous record and is not reported as success`() {
		val target = folder.newFile("record.json").apply { writeText("original") }
		assertThrows(IOException::class.java) {
			AtomicTextFile.write(target, "replacement") { _, _ -> throw IOException("disk failure") }
		}
		assertEquals("original", target.readText())
		assertEquals(listOf("record.json"), folder.root.list()!!.toList())
	}
	@Test fun `failed first save leaves no partial result`() {
		val target = File(folder.root, "record.json")
		assertThrows(IOException::class.java) {
			AtomicTextFile.write(target, "partial") { _, _ -> throw IOException("cannot move") }
		}
		assertFalse(target.exists())
		assertTrue(folder.root.list()!!.isEmpty())
	}
	@Test fun `invalid parent fails explicitly without deleting blocking file`() {
		val blocker = folder.newFile("parent").apply { writeText("keep") }
		assertThrows(IOException::class.java) { AtomicTextFile.write(File(blocker, "record.json"), "new") }
		assertEquals("keep", blocker.readText())
	}
}
