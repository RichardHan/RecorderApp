package com.eva.recordings.data.provider

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.eva.database.dao.RecordingsMetadataDao
import com.eva.database.dao.TrashFileDao
import com.eva.recordings.domain.models.TrashRecordingModel
import com.eva.utils.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrashRecordingsProviderApi29ImplTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val recordedAt = LocalDateTime(2026, 4, 1, 10, 0)
	private val expiresAt = LocalDateTime(2026, 5, 1, 10, 0)

	@Test
	fun `restore preserves backup file and metadata when MediaStore insert returns null`() = runTest {
		val fixture = Fixture()
		val backup = fixture.createBackupFile("rec_1.m4a", byteArrayOf(10, 20, 30))
		every { fixture.contentResolver.insert(fixture.volumeUri, any()) } returns null

		val result = fixture.provider.restoreRecordingsFromTrash(listOf(fixture.trashModel(1L, backup)))

		assertIs<Resource.Error<*, Exception>>(result)
		assertTrue(backup.exists(), "Recovery copy must stay intact when MediaStore insert fails")
		assertContentEquals(byteArrayOf(10, 20, 30), backup.readBytes())
		coVerify(exactly = 0) { fixture.trashDao.deleteTrashEntity(any()) }
		coVerify(exactly = 0) { fixture.recordingsDao.updateOrInsertRecordingMetadataBulk(any()) }
	}

	@Test
	fun `restore preserves backup file and deletes partial MediaStore row when openOutputStream returns null`() = runTest {
		val fixture = Fixture()
		val backup = fixture.createBackupFile("rec_2.m4a", byteArrayOf(1, 2, 3, 4))
		val insertedUri = mockk<Uri>()
		every { fixture.contentResolver.insert(fixture.volumeUri, any()) } returns insertedUri
		every { fixture.contentResolver.openOutputStream(insertedUri, "w") } returns null

		val result = fixture.provider.restoreRecordingsFromTrash(listOf(fixture.trashModel(2L, backup)))

		assertIs<Resource.Error<*, Exception>>(result)
		assertTrue(backup.exists(), "Recovery copy must stay intact when output stream cannot be opened")
		verify(exactly = 1) { fixture.contentResolver.delete(insertedUri, null, null) }
		coVerify(exactly = 0) { fixture.trashDao.deleteTrashEntity(any()) }
		coVerify(exactly = 0) { fixture.recordingsDao.updateOrInsertRecordingMetadataBulk(any()) }
	}

	@Test
	fun `restore preserves backup file and cleans up partial MediaStore row when stream copy throws`() = runTest {
		val fixture = Fixture()
		val expectedBytes = ByteArray(1024) { (it % 127).toByte() }
		val backup = fixture.createBackupFile("rec_3.m4a", expectedBytes)
		val insertedUri = mockk<Uri>()
		every { fixture.contentResolver.insert(fixture.volumeUri, any()) } returns insertedUri
		every { fixture.contentResolver.openOutputStream(insertedUri, "w") } returns object : OutputStream() {
			override fun write(b: Int) = throw IOException("Disk full")
			override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Disk full")
		}

		val result = fixture.provider.restoreRecordingsFromTrash(listOf(fixture.trashModel(3L, backup)))

		assertIs<Resource.Error<*, Exception>>(result)
		assertTrue(backup.exists(), "Recovery copy must not be deleted when copying bytes fails")
		assertContentEquals(expectedBytes, backup.readBytes())
		verify(exactly = 1) { fixture.contentResolver.delete(insertedUri, null, null) }
		coVerify(exactly = 0) { fixture.trashDao.deleteTrashEntity(any()) }
		coVerify(exactly = 0) { fixture.recordingsDao.updateOrInsertRecordingMetadataBulk(any()) }
	}

	@Test
	fun `restore preserves backup file when finalizing IS_PENDING update affects zero rows`() = runTest {
		val fixture = Fixture()
		val backup = fixture.createBackupFile("rec_4.m4a", byteArrayOf(7, 8, 9))
		val insertedUri = mockk<Uri>()
		every { fixture.contentResolver.insert(fixture.volumeUri, any()) } returns insertedUri
		every { fixture.contentResolver.openOutputStream(insertedUri, "w") } returns ByteArrayOutputStream()
		every { fixture.contentResolver.update(insertedUri, any(), null, null) } returns 0

		val result = fixture.provider.restoreRecordingsFromTrash(listOf(fixture.trashModel(4L, backup)))

		assertIs<Resource.Error<*, Exception>>(result)
		assertTrue(backup.exists(), "Recovery copy must stay intact when MediaStore finalization fails")
		verify(exactly = 1) { fixture.contentResolver.delete(insertedUri, null, null) }
		coVerify(exactly = 0) { fixture.trashDao.deleteTrashEntity(any()) }
		coVerify(exactly = 0) { fixture.recordingsDao.updateOrInsertRecordingMetadataBulk(any()) }
	}

	@Test
	fun `restore removes backup file and metadata only after MediaStore copy and finalize succeed`() = runTest {
		val fixture = Fixture()
		val payload = byteArrayOf(42, 43, 44, 45, 46)
		val backup = fixture.createBackupFile("rec_5.m4a", payload)
		val insertedUri = mockk<Uri>()
		val written = ByteArrayOutputStream()
		every { fixture.contentResolver.insert(fixture.volumeUri, any()) } returns insertedUri
		every { fixture.contentResolver.openOutputStream(insertedUri, "w") } returns written
		every { fixture.contentResolver.update(insertedUri, any(), null, null) } returns 1

		val result = fixture.provider.restoreRecordingsFromTrash(listOf(fixture.trashModel(5L, backup)))

		assertIs<Resource.Success<Unit, *>>(result)
		assertContentEquals(payload, written.toByteArray())
		assertFalse(backup.exists(), "Backup file should be cleaned up once restore succeeds")
		coVerify(exactly = 1) { fixture.trashDao.deleteTrashEntity(any()) }
		coVerify(exactly = 1) { fixture.recordingsDao.updateOrInsertRecordingMetadataBulk(any()) }
	}

	private inner class Fixture {
		val contentResolver = mockk<ContentResolver>(relaxed = true)
		val context = mockk<Context>(relaxed = true) {
			every { this@mockk.contentResolver } returns this@Fixture.contentResolver
			every { filesDir } returns tempFolder.root
			every { getString(any()) } returns "message"
		}
		val trashDao = mockk<TrashFileDao>(relaxed = true)
		val recordingsDao = mockk<RecordingsMetadataDao>(relaxed = true)
		val volumeUri = mockk<Uri>()

		val provider = TrashRecordingsProviderApi29Impl(
			context = context,
			trashMediaDao = trashDao,
			recordingsDao = recordingsDao,
			audioVolumeUriProvider = { volumeUri },
		)

		init {
			coEvery { trashDao.deleteTrashEntity(any()) } returns Unit
			coEvery { recordingsDao.updateOrInsertRecordingMetadataBulk(any()) } returns Unit
		}

		fun createBackupFile(name: String, bytes: ByteArray): File =
			tempFolder.newFile(name).apply { writeBytes(bytes) }

		fun trashModel(id: Long, file: File) = TrashRecordingModel(
			id = id,
			title = "title_$id",
			displayName = file.name,
			mimeType = "audio/mp4",
			recordedAt = recordedAt,
			fileUri = file.toURI().toString(),
			expiresAt = expiresAt,
		)
	}
}
