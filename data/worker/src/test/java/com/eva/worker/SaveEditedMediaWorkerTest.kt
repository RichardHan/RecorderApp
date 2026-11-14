package com.eva.worker

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.eva.recordings.domain.tasks.SaveEditMediaItemTask
import com.eva.utils.NotificationConstants
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SaveEditedMediaWorkerTest {

	@Test
	fun `SaveEditedMediaWorker overrides suspend getForegroundInfo for pre-Android 12 expedited work`() = runTest {
		val overridingMethod = SaveEditedMediaWorker::class.java.declaredMethods.firstOrNull { method ->
			method.name == "getForegroundInfo" &&
				method.parameterTypes.size == 1 &&
				kotlin.coroutines.Continuation::class.java.isAssignableFrom(method.parameterTypes[0])
		}
		assertTrue(
			overridingMethod != null && overridingMethod.declaringClass == SaveEditedMediaWorker::class.java,
			"SaveEditedMediaWorker must override CoroutineWorker.getForegroundInfo()",
		)

		mockkConstructor(Notification.Builder::class)
		try {
			val expectedNotification = mockk<Notification>()
			every { anyConstructed<Notification.Builder>().setSmallIcon(any<Int>()) } answers { self as Notification.Builder }
			every { anyConstructed<Notification.Builder>().setOngoing(any()) } answers { self as Notification.Builder }
			every { anyConstructed<Notification.Builder>().setContentTitle(any()) } answers { self as Notification.Builder }
			every { anyConstructed<Notification.Builder>().setProgress(any(), any(), any()) } answers { self as Notification.Builder }
			every { anyConstructed<Notification.Builder>().setContentText(any()) } answers { self as Notification.Builder }
			every { anyConstructed<Notification.Builder>().build() } returns expectedNotification

			val appContext = mockk<Context>(relaxed = true) {
				every { getString(any()) } returns "Saving edited recording"
			}
			val context = mockk<Context>(relaxed = true) {
				every { applicationContext } returns appContext
			}
			val params = mockk<WorkerParameters>(relaxed = true)
			val task = mockk<SaveEditMediaItemTask>(relaxed = true)

			val worker: CoroutineWorker = SaveEditedMediaWorker(context, params, task)
			val info = worker.getForegroundInfo()

			assertEquals(NotificationConstants.SAVE_EDITED_MEDIA_FOREGROUND_ID, info.notificationId)
			assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
			assertEquals(expectedNotification, info.notification)
		} finally {
			unmockkConstructor(Notification.Builder::class)
		}
	}

	@Test
	fun `copyStreamsWithProgress writes exact bytes across partial reads and EOF without stale buffer tail`() = runTest {
		// Non-multiple of 2048 bytes with distinct non-zero byte pattern across chunks
		val expected = ByteArray(5_003) { index -> ((index * 37 + 13) and 0xFF).toByte() }
		val chunkSizes = intArrayOf(2048, 700, 13, 2048, 1, 193)
		val fragmentedInput = object : InputStream() {
			private var offset = 0
			private var step = 0

			override fun read(): Int {
				if (offset >= expected.size) return -1
				return expected[offset++].toInt() and 0xFF
			}

			override fun read(b: ByteArray, off: Int, len: Int): Int {
				if (offset >= expected.size) return -1
				val planned = if (step < chunkSizes.size) chunkSizes[step++] else len
				val count = min(min(len, planned), expected.size - offset)
				System.arraycopy(expected, offset, b, off, count)
				offset += count
				return count
			}
		}

		val output = ByteArrayOutputStream()
		val reportedProgress = mutableListOf<Float>()

		SaveEditedMediaWorker.copyStreamsWithProgress(
			inputStream = fragmentedInput,
			outputStream = output,
			totalBytes = expected.size.toLong(),
			bufferSize = 2048,
			onProgress = { progress -> reportedProgress += progress },
		)

		val actual = output.toByteArray()
		assertEquals(expected.size, actual.size, "Output size must equal input size with no trailing bytes")
		assertContentEquals(expected, actual)
		assertTrue(reportedProgress.isNotEmpty())
		assertTrue(reportedProgress.all { it in 0f..1f && !it.isNaN() })
		assertEquals(1f, reportedProgress.last(), 1e-6f)
	}

	@Test
	fun `copyStreamsWithProgress handles empty stream safely without writing bytes or NaN progress`() = runTest {
		val output = ByteArrayOutputStream()
		val reportedProgress = mutableListOf<Float>()

		SaveEditedMediaWorker.copyStreamsWithProgress(
			inputStream = ByteArray(0).inputStream(),
			outputStream = output,
			totalBytes = 0L,
			bufferSize = 2048,
			onProgress = { reportedProgress += it },
		)

		assertEquals(0, output.size())
		assertEquals(listOf(1f), reportedProgress)
	}
}
