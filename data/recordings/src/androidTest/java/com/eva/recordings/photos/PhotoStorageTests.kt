package com.eva.recordings.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eva.database.RecorderDataBase
import com.eva.recordings.data.photos.RecordingPhotosImpl
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoStorageTests {
	@Test fun realImageResizesAndMetadataCascadeRemovesPrivateFiles() = runBlocking {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val db = RecorderDataBase.createInMemoryDatabase(context)
		val repo = RecordingPhotosImpl(context, db.recordingPhotosDao(), db.recordingMetaData())
		val source = File(context.cacheDir, "photo-source.png")
		try {
			val bitmap = Bitmap.createBitmap(3200, 2400, Bitmap.Config.ARGB_8888)
			source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
			bitmap.recycle()
			repo.beginSession()
			val owner = repo.activeOwner.value!!
			repo.add(owner, 1234, Uri.fromFile(source), "白板")
			repo.finishSession(7)
			val photo = db.recordingPhotosDao().all().single()
			assertEquals(7L, photo.recordingId)
			assertEquals(1234L, photo.startMs)
			val file = File(context.noBackupFilesDir, "recording_photos/${photo.relativePath}")
			val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(file.path, bounds)
			assertEquals(1600, bounds.outWidth)
			assertEquals(1200, bounds.outHeight)
			assertEquals("image/jpeg", bounds.outMimeType)
			db.recordingMetaData().deleteRecordingMetaDataFromIds(listOf(7))
			repo.pruneDeletedFiles()
			assertTrue(db.recordingPhotosDao().all().isEmpty())
			assertFalse(file.exists())
			assertTrue(source.exists())
		} finally { repo.stopCleanup(); source.delete(); db.close() }
	}
}
