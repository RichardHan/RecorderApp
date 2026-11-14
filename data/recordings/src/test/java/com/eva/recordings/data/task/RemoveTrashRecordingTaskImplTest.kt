package com.eva.recordings.data.task

import com.eva.recordings.domain.models.TrashRecordingModel
import com.eva.recordings.domain.provider.TrashRecordingsProvider
import com.eva.utils.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalTime::class)
class RemoveTrashRecordingTaskImplTest {

	@get:Rule
	val tempFolder = TemporaryFolder()

	private val now = Instant.fromEpochMilliseconds(1_750_000_000_000L)
	private val timeZone = TimeZone.currentSystemDefault()
	private val recordedAt = (now - 2.days).toLocalDateTime(timeZone)

	@Test
	fun `evaluateItemsToRemove keeps valid non-empty future-expiring backup files`() {
		val provider = mockk<TrashRecordingsProvider>(relaxed = true)
		val task = RemoveTrashRecordingTaskImpl(provider)

		val validBackup = tempFolder.newFile("valid_backup.m4a").apply {
			writeBytes(ByteArray(4096) { 0x2A })
		}
		val emptyBackup = tempFolder.newFile("empty_backup.m4a")
		val missingBackup = File(tempFolder.root, "missing_backup.m4a")
		val expiredBackup = tempFolder.newFile("expired_backup.m4a").apply {
			writeBytes(ByteArray(1024) { 0x11 })
		}

		val validItem = model(
			id = 1L,
			file = validBackup,
			expiresAtInstant = now + 29.days,
		)
		val emptyItem = model(
			id = 2L,
			file = emptyBackup,
			expiresAtInstant = now + 29.days,
		)
		val missingItem = model(
			id = 3L,
			file = missingBackup,
			expiresAtInstant = now + 29.days,
		)
		val expiredItem = model(
			id = 4L,
			file = expiredBackup,
			expiresAtInstant = now + 30.minutes,
		)
		val contentUriItem = TrashRecordingModel(
			id = 5L,
			title = "content_item",
			displayName = "content_item.m4a",
			mimeType = "audio/mp4",
			recordedAt = recordedAt,
			fileUri = "content://media/external/audio/media/5",
			expiresAt = (now + 10.days).toLocalDateTime(timeZone),
		)

		val toRemove = task.evaluateItemsToRemove(
			items = listOf(validItem, emptyItem, missingItem, expiredItem, contentUriItem),
			currentInstant = now,
		)

		val removedIds = toRemove.map { it.id }.toSet()
		assertFalse(1L in removedIds, "Non-empty non-expired backup must not be deleted prematurely")
		assertFalse(5L in removedIds, "Non-expired content:// entry must not be treated as missing file")
		assertEquals(setOf(2L, 3L, 4L), removedIds)
	}

	@Test
	fun `invoke returns success and deletes only expired or invalid backups on API 29`() = runTest {
		// Build.VERSION.SDK_INT defaults to 0 (< 30) in JVM unit tests, exercising the API 29 branch
		val validBackup = tempFolder.newFile("keep_me.m4a").apply {
			writeBytes(byteArrayOf(1, 2, 3, 4, 5))
		}
		val missingBackup = File(tempFolder.root, "gone.m4a")

		val validItem = model(
			id = 10L,
			file = validBackup,
			expiresAtInstant = kotlin.time.Clock.System.now() + 15.days,
		)
		val missingItem = model(
			id = 11L,
			file = missingBackup,
			expiresAtInstant = kotlin.time.Clock.System.now() + 15.days,
		)

		val deletedSlot = slot<List<TrashRecordingModel>>()
		val provider = mockk<TrashRecordingsProvider>()
		coEvery { provider.getTrashedVoiceRecordings() } returns Resource.Success(listOf(validItem, missingItem))
		coEvery { provider.permanentlyDeleteRecordings(capture(deletedSlot)) } returns Resource.Success(Unit)

		val result = RemoveTrashRecordingTaskImpl(provider).invoke()

		assertTrue(result.isSuccess)
		assertEquals(listOf(11L), deletedSlot.captured.map { it.id })
		assertTrue(validBackup.exists())
		coVerify(exactly = 1) { provider.permanentlyDeleteRecordings(any()) }
	}

	private fun model(
		id: Long,
		file: File,
		expiresAtInstant: Instant,
	) = TrashRecordingModel(
		id = id,
		title = "rec_$id",
		displayName = "rec_$id.m4a",
		mimeType = "audio/mp4",
		recordedAt = recordedAt,
		fileUri = file.toURI().toString(),
		expiresAt = expiresAtInstant.toLocalDateTime(timeZone),
	)
}
