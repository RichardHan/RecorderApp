package com.eva.drive

import android.content.Context
import android.net.Network
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.eva.database.dao.DriveUploadDao
import com.eva.database.entity.DriveUploadEntity
import com.eva.drive.data.network.DriveUploaderImpl
import com.eva.drive.data.preferences.RecordingAutoUploader
import com.eva.drive.data.repository.DriveRecordingsProvider
import com.eva.drive.data.repository.RecordingUploadTaskImpl
import com.eva.drive.domain.auth.GoogleDriveAuth
import com.eva.drive.domain.models.DriveSettings
import com.eva.drive.domain.models.DriveUploadStatus
import com.eva.drive.domain.provider.DriveClientFactory
import com.eva.drive.domain.provider.DriveNetworkChecker
import com.eva.drive.domain.provider.DriveRecording
import com.eva.drive.domain.provider.DriveUploadException
import com.eva.drive.domain.provider.DriveUploadedFile
import com.eva.drive.domain.provider.DriveUploader
import com.eva.drive.domain.repository.DriveSettingsRepository
import com.eva.drive.worker.DriveUploadWorker
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.FilterInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DriveUploadFixesTest {

	@Test
	fun `queued upload skips work and removes pending metadata when autoUploadEnabled toggled off`() = runTest {
		val harness = WorkerHarness(
			initialSettings = DriveSettings(
				accountEmail = "user@example.com",
				autoUploadEnabled = false,
				wifiOnly = false,
			),
		)
		harness.seedEntity(recordingId = 42L, status = DriveUploadStatus.PENDING.name)

		val result = harness.createWorker(recordingId = 42L, runAttemptCount = 0).doWork()

		assertEquals(ListenableWorker.Result.success(), result)
		assertNull(harness.dao.getUploadFromId(42L), "Pending upload row should be removed on opt-out")
		assertEquals(0, harness.uploadCalls)
		coVerify(exactly = 0) { harness.auth.accessToken() }
	}

	@Test
	fun `queued upload skips work and removes pending metadata when user signs out after enqueue`() = runTest {
		val harness = WorkerHarness(
			initialSettings = DriveSettings(
				accountEmail = null,
				autoUploadEnabled = true,
				wifiOnly = false,
			),
		)
		harness.seedEntity(recordingId = 43L, status = DriveUploadStatus.PENDING.name)

		val result = harness.createWorker(recordingId = 43L, runAttemptCount = 0).doWork()

		assertEquals(ListenableWorker.Result.success(), result)
		assertNull(harness.dao.getUploadFromId(43L))
		assertEquals(0, harness.uploadCalls)
		coVerify(exactly = 0) { harness.auth.accessToken() }
	}

	@Test
	fun `queued upload preserves already-succeeded row if duplicate worker runs after opt-out`() = runTest {
		val harness = WorkerHarness(
			initialSettings = DriveSettings(
				accountEmail = "user@example.com",
				autoUploadEnabled = false,
			),
		)
		harness.seedEntity(
			recordingId = 44L,
			status = DriveUploadStatus.SUCCESS.name,
			fileId = "drive-file-44",
		)

		val result = harness.createWorker(recordingId = 44L, runAttemptCount = 0).doWork()

		assertEquals(ListenableWorker.Result.success(), result)
		assertEquals(DriveUploadStatus.SUCCESS.name, harness.dao.getUploadFromId(44L)?.status)
		assertEquals(0, harness.uploadCalls)
	}

	@Test
	fun `queued upload updates work constraints to UNMETERED and retries when wifiOnly enabled on metered network`() = runTest {
		val workId = UUID.randomUUID()
		val harness = WorkerHarness(
			initialSettings = DriveSettings(
				accountEmail = "user@example.com",
				autoUploadEnabled = true,
				wifiOnly = true,
			),
			isUnmeteredNetwork = false,
		)
		harness.seedEntity(recordingId = 55L, status = DriveUploadStatus.PENDING.name)
		val updatedRequestSlot = slot<WorkRequest>()
		every { harness.workManager.updateWork(capture(updatedRequestSlot)) } returns mockk(relaxed = true)

		val result = harness.createWorker(
			recordingId = 55L,
			runAttemptCount = 0,
			workId = workId,
		).doWork()

		assertEquals(ListenableWorker.Result.retry(), result)
		assertEquals(DriveUploadStatus.PENDING.name, harness.dao.getUploadFromId(55L)?.status)
		assertEquals(0, harness.uploadCalls)
		coVerify(exactly = 0) { harness.auth.accessToken() }
		verify(exactly = 1) { harness.workManager.updateWork(any()) }
		assertEquals(workId, updatedRequestSlot.captured.id)
		assertEquals(
			NetworkType.UNMETERED,
			updatedRequestSlot.captured.workSpec.constraints.requiredNetworkType,
		)
	}

	@Test
	fun `opt-out during recording lookup closes audio stream and aborts before network upload`() = runTest {
		val streamClosed = AtomicBoolean(false)
		val harness = WorkerHarness(
			initialSettings = DriveSettings(
				accountEmail = "user@example.com",
				autoUploadEnabled = true,
				wifiOnly = false,
			),
		)
		harness.seedEntity(recordingId = 56L, status = DriveUploadStatus.PENDING.name)

		coEvery { harness.recordingsProvider.recording(56L) } coAnswers {
			// User opts out while worker is opening the local audio file
			harness.settingsFlow.value = harness.settingsFlow.value.copy(autoUploadEnabled = false)
			val trackedStream = object : FilterInputStream(byteArrayOf(1, 2, 3).inputStream()) {
				override fun close() {
					streamClosed.set(true)
					super.close()
				}
			}
			Result.success(
				DriveRecording(
					recordingId = 56L,
					fileName = "rec_56.m4a",
					mimeType = "audio/mp4",
					sizeBytes = 3L,
					media = InputStreamContent("audio/mp4", trackedStream),
				),
			)
		}

		val result = harness.createWorker(recordingId = 56L, runAttemptCount = 0).doWork()

		assertEquals(ListenableWorker.Result.success(), result)
		assertTrue(streamClosed.get(), "Opened recording InputStream must be closed when aborting on opt-out")
		assertNull(harness.dao.getUploadFromId(56L))
		assertEquals(0, harness.uploadCalls)
	}

	@Test
	fun `first HTTP 401 invalidates stale token keeps status PENDING and retries once before failing`() = runTest {
		val harness = WorkerHarness(
			initialSettings = DriveSettings(
				accountEmail = "user@example.com",
				autoUploadEnabled = true,
			),
			uploaderBehavior = { _, _ ->
				throw DriveUploadException(
					message = "Invalid Credentials",
					httpStatusCode = 401,
					errorReason = "authError",
				)
			},
		)
		harness.seedEntity(recordingId = 77L, status = DriveUploadStatus.PENDING.name)
		coEvery { harness.auth.accessToken() } returns Result.success("stale-token-1")

		// Attempt 0: invalidates token, keeps DB row PENDING, returns retry()
		val firstAttempt = harness.createWorker(recordingId = 77L, runAttemptCount = 0).doWork()
		assertEquals(ListenableWorker.Result.retry(), firstAttempt)
		assertEquals(DriveUploadStatus.PENDING.name, harness.dao.getUploadFromId(77L)?.status)
		coVerify(exactly = 1) { harness.auth.invalidateAccessToken("stale-token-1") }

		// Attempt 1: refreshed token also fails with 401 -> marks FAILED and stops retrying
		coEvery { harness.auth.accessToken() } returns Result.success("refreshed-token-2")
		val secondAttempt = harness.createWorker(recordingId = 77L, runAttemptCount = 1).doWork()
		assertEquals(ListenableWorker.Result.failure(), secondAttempt)
		assertEquals(DriveUploadStatus.FAILED.name, harness.dao.getUploadFromId(77L)?.status)
	}

	@Test
	fun `HTTP 403 rateLimitExceeded retries and stays PENDING whereas permission 403 marks FAILED`() = runTest {
		var reasonToThrow = "userRateLimitExceeded"
		val harness = WorkerHarness(
			initialSettings = DriveSettings(
				accountEmail = "user@example.com",
				autoUploadEnabled = true,
			),
			uploaderBehavior = { _, _ ->
				throw DriveUploadException(
					message = "403 $reasonToThrow",
					httpStatusCode = 403,
					errorReason = reasonToThrow,
					errorReasons = setOf(reasonToThrow),
				)
			},
		)
		harness.seedEntity(recordingId = 88L, status = DriveUploadStatus.PENDING.name)

		val rateLimitedResult = harness.createWorker(recordingId = 88L, runAttemptCount = 0).doWork()
		assertEquals(ListenableWorker.Result.retry(), rateLimitedResult)
		assertEquals(DriveUploadStatus.PENDING.name, harness.dao.getUploadFromId(88L)?.status)

		reasonToThrow = "insufficientFilePermissions"
		val forbiddenResult = harness.createWorker(recordingId = 88L, runAttemptCount = 1).doWork()
		assertEquals(ListenableWorker.Result.failure(), forbiddenResult)
		assertEquals(DriveUploadStatus.FAILED.name, harness.dao.getUploadFromId(88L)?.status)
	}

	@Test
	fun `DriveUploaderImpl maps GoogleJsonResponseException from pre-create calls into DriveUploadException and closes stream`() = runTest {
		val errorJson = """
			{
			  "error": {
			    "code": 403,
			    "message": "User Rate Limit Exceeded",
			    "errors": [
			      {
			        "domain": "usageLimits",
			        "reason": "userRateLimitExceeded",
			        "message": "User Rate Limit Exceeded"
			      }
			    ]
			  }
			}
		""".trimIndent()

		val transport = object : MockHttpTransport() {
			override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
				return object : MockLowLevelHttpRequest(url) {
					override fun execute(): LowLevelHttpResponse {
						return MockLowLevelHttpResponse()
							.setStatusCode(403)
							.setContentType("application/json; charset=UTF-8")
							.setContent(errorJson)
					}
				}
			}
		}
		val clientFactory = object : DriveClientFactory {
			override val transport: HttpTransport = transport
			override val jsonFactory: JsonFactory = GsonFactory.getDefaultInstance()
		}
		val uploader = DriveUploaderImpl(clientFactory)

		val streamClosed = AtomicBoolean(false)
		val trackedStream = object : FilterInputStream(byteArrayOf(9, 8, 7, 6).inputStream()) {
			override fun close() {
				streamClosed.set(true)
				super.close()
			}
		}
		val recording = DriveRecording(
			recordingId = 99L,
			fileName = "meeting.m4a",
			mimeType = "audio/mp4",
			sizeBytes = 4L,
			media = InputStreamContent("audio/mp4", trackedStream),
		)

		val thrown = assertFailsWith<DriveUploadException> {
			uploader.upload(
				accessToken = "token",
				targetFolderId = null,
				preferredFolderName = "RecorderApp",
				recording = recording,
			)
		}

		assertEquals(403, thrown.httpStatusCode)
		assertEquals("userRateLimitExceeded", thrown.errorReason)
		assertTrue(thrown.isRateLimitForbidden)
		assertTrue(!thrown.isTerminal(canRetryUnauthorized = false))
		assertTrue(streamClosed.get(), "DriveUploaderImpl must always close recording InputStream on pre-upload failure")
	}

	private class InMemoryDriveUploadDao : DriveUploadDao {
		private val map = LinkedHashMap<Long, DriveUploadEntity>()
		private val flow = MutableStateFlow<List<DriveUploadEntity>>(emptyList())

		fun seed(entity: DriveUploadEntity) {
			map[entity.recordingId] = entity
			flow.value = map.values.toList()
		}

		override suspend fun upsert(entity: DriveUploadEntity) {
			map[entity.recordingId] = entity
			flow.value = map.values.toList()
		}

		override suspend fun getUploadFromId(recordingId: Long): DriveUploadEntity? = map[recordingId]

		override fun getUploadFromIdAsFlow(recordingId: Long): Flow<DriveUploadEntity?> =
			flow.map { map[recordingId] }

		override fun getUploadsAsFlow(): Flow<List<DriveUploadEntity>> = flow

		override suspend fun remove(recordingId: Long) {
			map.remove(recordingId)
			flow.value = map.values.toList()
		}
	}

	private class WorkerHarness(
		initialSettings: DriveSettings,
		var isUnmeteredNetwork: Boolean = true,
		private val uploaderBehavior: (DriveRecording, String) -> DriveUploadedFile = { rec, _ ->
			DriveUploadedFile(
				id = "drive-${rec.recordingId}",
				folderId = "folder-1",
			)
		},
	) {
		val settingsFlow = MutableStateFlow(initialSettings)
		val settingsRepo = object : DriveSettingsRepository {
			override val settings: StateFlow<DriveSettings> = settingsFlow
			override suspend fun currentSettings(): DriveSettings = settingsFlow.value
			override suspend fun updateAccountAndFolder(accountEmail: String, targetFolderId: String?) {
				settingsFlow.value = settingsFlow.value.copy(
					accountEmail = accountEmail,
					targetFolderId = targetFolderId,
				)
			}
			override suspend fun setAutoUploadEnabled(enabled: Boolean) {
				settingsFlow.value = settingsFlow.value.copy(autoUploadEnabled = enabled)
			}
			override suspend fun setWifiOnlyEnabled(enabled: Boolean) {
				settingsFlow.value = settingsFlow.value.copy(wifiOnly = enabled)
			}
			override suspend fun setTargetFolderId(folderId: String) {
				settingsFlow.value = settingsFlow.value.copy(targetFolderId = folderId)
			}
			override suspend fun signOut() {
				settingsFlow.value = DriveSettings()
			}
		}

		val dao = InMemoryDriveUploadDao()
		val context = mockk<Context>(relaxed = true) {
			every { getString(any()) } returns "RecorderApp"
		}
		val workManager = mockk<WorkManager>(relaxed = true)
		val recordingsProvider = mockk<DriveRecordingsProvider>()
		val auth = mockk<GoogleDriveAuth>(relaxed = true)
		var uploadCalls = 0

		val uploader = object : DriveUploader {
			override fun upload(
				accessToken: String,
				targetFolderId: String?,
				preferredFolderName: String,
				recording: DriveRecording,
			): DriveUploadedFile {
				uploadCalls++
				return uploaderBehavior(recording, accessToken)
			}
		}

		val networkChecker = DriveNetworkChecker { isUnmeteredNetwork }

		val uploadTask = RecordingUploadTaskImpl(
			context = context,
			settingsRepository = settingsRepo,
			recordingsProvider = recordingsProvider,
			uploadDao = dao,
			uploader = uploader,
			networkChecker = networkChecker,
			workManagerProvider = { workManager },
		)

		val autoUploader = RecordingAutoUploader(uploadTask = uploadTask)

		init {
			coEvery { auth.accessToken() } returns Result.success("valid-token")
			coEvery { recordingsProvider.recording(any()) } answers {
				val id = firstArg<Long>()
				Result.success(
					DriveRecording(
						recordingId = id,
						fileName = "rec_$id.m4a",
						mimeType = "audio/mp4",
						sizeBytes = 4L,
						media = InputStreamContent("audio/mp4", byteArrayOf(1, 2, 3, 4).inputStream()),
					),
				)
			}
		}

		fun seedEntity(
			recordingId: Long,
			status: String,
			fileId: String? = null,
		) {
			dao.seed(
				DriveUploadEntity(
					recordingId = recordingId,
					fileId = fileId,
					status = status,
					updatedAt = 1_000L,
				),
			)
		}

		fun createWorker(
			recordingId: Long,
			runAttemptCount: Int,
			workId: UUID = UUID.randomUUID(),
			network: Network? = mockk(relaxed = true),
		): DriveUploadWorker {
			val inputData = Data.Builder()
				.putLong(DriveUploadWorker.RECORDING_ID, recordingId)
				.build()
			val params = mockk<WorkerParameters>(relaxed = true) {
				every { this@mockk.inputData } returns inputData
				every { this@mockk.runAttemptCount } returns runAttemptCount
				every { this@mockk.id } returns workId
				every { this@mockk.network } returns network
			}
			val worker = DriveUploadWorker(
				context = context,
				params = params,
				auth = auth,
				uploadTask = autoUploader,
			)
			assertNotNull(worker)
			return worker
		}
	}
}
