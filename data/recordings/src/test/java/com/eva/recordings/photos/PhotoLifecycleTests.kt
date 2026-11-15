package com.eva.recordings.photos

import android.content.Context
import android.net.Uri
import com.eva.database.dao.RecordingPhotosDao
import com.eva.database.dao.RecordingsMetadataDao
import com.eva.database.entity.*
import com.eva.recordings.data.photos.RecordingPhotosImpl
import io.mockk.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhotoLifecycleTests {
	private class Harness(failEncoding: Boolean = false) : AutoCloseable {
		val root = Files.createTempDirectory("photo-lifecycle").toFile()
		val rows = MutableStateFlow<List<RecordingPhotoEntity>>(emptyList())
		val sessions = mutableMapOf<String, PhotoSessionEntity>()
		val metadata = mutableSetOf<Long>()
		val dao = mockk<RecordingPhotosDao>()
		val meta = mockk<RecordingsMetadataDao>()
		val uri = mockk<Uri>()
		val repo: RecordingPhotosImpl
		init {
			val context = mockk<Context>()
			every { context.noBackupFilesDir } returns root
			every { dao.observeAll() } returns rows
			coEvery { dao.all() } answers { rows.value }
			coEvery { dao.session(any<PhotoSessionEntity>()) } answers { val s = firstArg<PhotoSessionEntity>(); sessions[s.owner] = s }
			coEvery { dao.session(any<String>()) } answers { sessions[firstArg()] }
			coEvery { dao.discardDrafts() } answers {
				rows.value = rows.value.filter { it.recordingId != null }
				sessions.entries.removeAll { it.value.recordingId == null }; Unit
			}
			coEvery { dao.discard(any()) } answers {
				val owner = firstArg<String>(); rows.value = rows.value.filter { it.owner != owner }; sessions.remove(owner); Unit
			}
			coEvery { dao.count(any(), any()) } answers {
				val owner = firstArg<String>(); val id = secondArg<Long?>()
				rows.value.count { it.owner == owner || (id != null && it.recordingId == id) }
			}
			coEvery { dao.insert(any()) } answers { rows.value = rows.value + firstArg<RecordingPhotoEntity>() }
			coEvery { dao.bind(any(), any()) } answers {
				val owner = firstArg<String>(); val id = secondArg<Long>()
				sessions[owner] = sessions.getValue(owner).copy(recordingId = id)
				rows.value = rows.value.map { if (it.owner == owner) it.copy(recordingId = id) else it }
			}
			coEvery { dao.delete(any()) } answers { val id = firstArg<String>(); rows.value = rows.value.filter { it.id != id } }
			coEvery { meta.addRecordingMetaDataBulk(any()) } answers { metadata += firstArg<Collection<RecordingsMetaDataEntity>>().map { it.recordingId }; Unit }
			coEvery { meta.getRecordingMetaDataFromId(any()) } answers { firstArg<Long>().takeIf { it in metadata }?.let { RecordingsMetaDataEntity(it) } }
			repo = RecordingPhotosImpl(context, dao, meta) { _, file -> file.writeText("encoded jpeg"); check(!failEncoding) }
		}
		override fun close() { repo.stopCleanup(); root.deleteRecursively() }
	}

	@Test fun lateCameraResultKeepsOriginalRecordingAndTapTime() = runBlocking {
		Harness().use { h ->
			h.repo.beginSession()
			val owner = h.repo.activeOwner.value!!
			h.repo.add(owner, 1234L, h.uri, "whiteboard")
			h.repo.finishSession(7)
			h.repo.beginSession()
			h.repo.add(owner, 4567L, h.uri, "late photo")
			assertEquals(listOf(7L, 7L), h.rows.value.map { it.recordingId })
			assertEquals(listOf(1234L, 4567L), h.rows.value.map { it.startMs })
			assertNotEquals(owner, h.repo.activeOwner.value)
		}
	}

	@Test fun cancelRejectsLateResultAndRemovesFiles() = runBlocking {
		Harness().use { h ->
			h.repo.beginSession(); val owner = h.repo.activeOwner.value!!
			h.repo.add(owner, 100, h.uri, "")
			h.repo.cancelSession()
			assertTrue(h.rows.value.isEmpty())
			assertTrue(File(h.root, "recording_photos").listFiles()!!.isEmpty())
			assertTrue(runCatching { h.repo.add(owner, 100, h.uri, "") }.isFailure)
		}
	}

	@Test fun limitAndCascadeCleanupDoNotDeleteSourceImage() = runBlocking {
		Harness().use { h ->
			h.repo.prepareRecording(7)
			repeat(30) { h.repo.add("recording:7", it.toLong(), h.uri, "x".repeat(100)) }
			assertTrue(runCatching { h.repo.add("recording:7", 31, h.uri, "") }.isFailure)
			assertEquals(30, h.rows.value.size)
			assertTrue(h.rows.value.all { it.caption.length == 80 })
			// Room's actual cascade is separately covered by the migration instrumented test.
			h.metadata.clear(); h.rows.value = emptyList()
			h.repo.pruneDeletedFiles()
			assertTrue(File(h.root, "recording_photos").listFiles()!!.isEmpty())
			assertTrue(runCatching { h.repo.add("recording:7", 32, h.uri, "") }.isFailure)
		}
	}

	@Test fun failedEncodingLeavesNoRowOrPartialFile() = runBlocking {
		Harness(failEncoding = true).use { h ->
			h.repo.prepareRecording(7)
			assertTrue(runCatching { h.repo.add("recording:7", 100, h.uri, "") }.isFailure)
			assertTrue(h.rows.value.isEmpty())
			assertTrue(File(h.root, "recording_photos").listFiles()!!.isEmpty())
		}
	}
}
