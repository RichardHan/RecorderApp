package io.github.richardhan.voicenote

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StudioCaptureTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	private val app = instrumentation.targetContext.applicationContext as StudioApplication
	private val library get() = app.library

	private fun waitFor(condition: () -> Boolean) {
		val end = SystemClock.elapsedRealtime() + 10000
		while (SystemClock.elapsedRealtime() < end) {
			var met = false
			instrumentation.runOnMainSync { met = condition() }
			if (met) return
			SystemClock.sleep(100)
		}
		fail("Timed out waiting for recording state")
	}

	@Test fun notificationSavesRecordingAfterActivityCloses() {
		val automation = instrumentation.uiAutomation
		automation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
		if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
		val activity = instrumentation.startActivitySync(Intent(app, StudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		val notifications = app.getSystemService(NotificationManager::class.java)
		val before = library.files.toSet()
		var result: File? = null
		try {
			instrumentation.runOnMainSync {
				library.configure(CaptureFormat.AAC, 1)
				activity.controlCapture(CaptureAction.START)
			}
			waitFor { library.started != 0L }
			SystemClock.sleep(650)
			instrumentation.runOnMainSync { library.captureBookmark(); activity.finish() }
			waitFor { activity.isDestroyed }
			fun press(label: Int) = notifications.activeNotifications.single { it.id == 10 }.notification.actions
				.single { it.title == app.getString(label) }.actionIntent.send()
			press(R.string.pause_recording)
			waitFor { library.isPaused }
			press(R.string.resume_recording)
			waitFor { !library.isPaused }
			SystemClock.sleep(400)
			press(R.string.stop)
			waitFor { library.started == 0L && (library.files - before).size == 1 }
			val file = (library.files - before).single()
			result = file
			val extractor = MediaExtractor()
			try {
				extractor.setDataSource(file.path)
				assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
			} finally { extractor.release() }
			assertTrue(LibraryCatalog(app).markers(file).single().ms > 0)
		} finally {
			instrumentation.runOnMainSync { activity.finish(); app.stopService(Intent(app, CaptureService::class.java)) }
			result?.delete()
			instrumentation.runOnMainSync { library.refresh() }
		}
	}

	@Test fun recordsEveryCodecAdvertisedByTheDevice() {
		instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
		val activity = instrumentation.startActivitySync(Intent(app, StudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		val previous = library.format
		val quality = library.quality
		try {
			val formats = supportedCaptureFormats()
			assertTrue(CaptureFormat.AAC in formats)
			for (format in formats) {
				val before = library.files.toSet()
				instrumentation.runOnMainSync { library.configure(format, 1); activity.controlCapture(CaptureAction.START) }
				waitFor { library.started != 0L }
				SystemClock.sleep(750)
				instrumentation.runOnMainSync { activity.controlCapture(CaptureAction.FINISH) }
				waitFor { library.started == 0L }
				val saved = (library.files - before).single()
				try {
					assertEquals(format.ext, saved.extension)
					val media = android.media.MediaMetadataRetriever()
					try {
						media.setDataSource(saved.path)
						assertTrue(requireNotNull(media.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong() > 0)
					} finally { media.release() }
				} finally {
					saved.delete()
					instrumentation.runOnMainSync { library.refresh() }
				}
			}
		} finally {
			instrumentation.runOnMainSync { library.configure(previous, quality); activity.finish(); app.stopService(Intent(app, CaptureService::class.java)) }
		}
	}

	@Test fun catalogRestoresOriginalRecordingAndMetadata() {
		val file = File.createTempFile("catalog-", ".m4a", File(app.filesDir, "audio").apply { mkdirs() })
		try {
			val catalog = library.catalog
			catalog.update(file, "title" to "Meet", "category" to "Work")
			val marks = listOf(RecordingMarker(15, "a,\"b\""))
			catalog.saveMarkers(file, marks)
			library.refresh()
			library.trash(file)
			assertFalse(file in library.files)
			assertTrue(file in library.trashedFiles)
			val reopened = LibraryCatalog(app)
			assertEquals("Work", reopened.value(file, "category"))
			assertEquals(marks, reopened.markers(file))
			assertEquals("milliseconds,label\r\n15,\"a,\"\"b\"\"\"\r\n", markerCsv(marks))
			catalog.update(file, "trashed" to "")
			assertEquals("Meet", catalog.title(file))
			assertTrue(file in library.files)
		} finally { file.delete(); library.refresh() }
	}
}
