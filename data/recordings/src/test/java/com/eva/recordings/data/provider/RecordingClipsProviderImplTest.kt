package com.eva.recordings.data.provider

import com.eva.database.dao.RecordingClipsDao
import com.eva.database.entity.RecordingClipEntity
import com.eva.recordings.domain.models.RecordingClipModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingClipsProviderImplTest {

	@get:Rule
	val temp = TemporaryFolder()

	private val dao = mockk<RecordingClipsDao>(relaxed = true)

	private fun provider(root: File) = RecordingClipsProviderImpl(dao, rootDirectory = { root }, clock = { 1234L })

	@Test
	fun `save moves the exported file into the clips folder and stores the user range`() = runTest {
		val root = temp.newFolder("clips")
		val exported = temp.newFile("temp_1.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
		val saved = slot<RecordingClipEntity>()
		coEvery { dao.insertOrUpdate(capture(saved)) } returns 9L

		val result = provider(root).saveExportedClip(7L, "  結論\n ", 20_000L, 50_000L, "audio/mp4a-latm", exported)

		val clip = result.getOrThrow()
		assertEquals(9L, clip.id)
		assertEquals("結論", clip.title)
		assertEquals(20_000L, saved.captured.startMs)
		assertEquals(50_000L, saved.captured.endMs)
		assertFalse(exported.exists())
		val stored = File(clip.filePath)
		assertTrue(stored.exists())
		assertEquals(File(root, "7"), stored.parentFile)
	}

	@Test
	fun `failed insert leaves no file behind`() = runTest {
		val root = temp.newFolder("clips")
		val exported = temp.newFile("temp_2.m4a").apply { writeBytes(byteArrayOf(1)) }
		coEvery { dao.insertOrUpdate(any()) } throws IllegalStateException("db")

		val result = provider(root).saveExportedClip(7L, "x", 0L, 2_000L, "audio/mp4a-latm", exported)

		assertTrue(result.isFailure)
		assertFalse(exported.exists())
		assertEquals(0, File(root, "7").listFiles()?.size ?: 0)
	}

	@Test
	fun `delete removes only the clip file and row`() = runTest {
		val root = temp.newFolder("clips")
		val source = temp.newFile("source.m4a")
		val clipFile = File(root, "7/a.m4a").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
		val clip = RecordingClipModel(3L, 7L, "t", 0L, 1_000L, clipFile.absolutePath, "audio/mp4a-latm", 0L)

		provider(root).delete(clip)

		assertFalse(clipFile.exists())
		assertTrue(source.exists())
		coVerify { dao.deleteById(3L) }
	}

	@Test
	fun `delete refuses paths outside the clips folder`() = runTest {
		val root = temp.newFolder("clips")
		val outside = temp.newFile("recording.m4a")
		val clip = RecordingClipModel(4L, 7L, "t", 0L, 1_000L, outside.absolutePath, "audio/mp4a-latm", 0L)

		provider(root).delete(clip)

		assertTrue(outside.exists())
	}

	@Test
	fun `delete all for trashed recordings removes their files and rows`() = runTest {
		val root = temp.newFolder("clips")
		val clipFile = File(root, "7/a.m4a").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
		val other = File(root, "8/b.m4a").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
		coEvery { dao.clipsOf(listOf(7L)) } returns listOf(
			RecordingClipEntity(1L, 7L, "t", 0L, 1_000L, clipFile.absolutePath, "audio/mp4a-latm", 0L),
		)

		provider(root).deleteAllFor(listOf(7L))

		assertFalse(clipFile.exists())
		assertFalse(File(root, "7").exists())
		assertTrue(other.exists())
		coVerify { dao.deleteBySourceIds(listOf(7L)) }
	}
}
