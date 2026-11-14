package com.eva.feature_player.cloud

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import com.eva.cloudtranscribe.model.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptExportViewModelTest {
	@get:Rule val folder = TemporaryFolder()
	@Before fun setup() {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		mockkStatic(Toast::class)
		every { Toast.makeText(any<Context>(), any<Int>(), any<Int>()) } returns mockk(relaxed = true)
	}
	@After fun teardown() { unmockkStatic(Toast::class); Dispatchers.resetMain() }

	@Test fun `recreated export uses saved immutable snapshot and cleans it after writing`() = runTest {
		val context = mockk<Context>()
		every { context.cacheDir } returns folder.root
		val resolver = mockk<ContentResolver>()
		every { context.contentResolver } returns resolver
		val output = ByteArrayOutputStream()
		every { resolver.openOutputStream(any<Uri>(), "wt") } returns output
		val state = SavedStateHandle()
		val original = TranscriptExportViewModel(context, state)
		val record = TranscriptRecord("t", 1, TranscriptSource.OFFLINE,
			Provenance("offline", model = "test"), 0, status = RunStatus.SUCCESS, text = "Original snapshot")
		original.prepare(record)!!.join()
		assertTrue(original.needsDestination)
		val recreatedState = SavedStateHandle(mapOf("pendingExport" to state.get<String>("pendingExport")))
		val recreated = TranscriptExportViewModel(context, recreatedState)
		assertTrue(recreated.busy)
		recreated.destinationChosen(mockk()).join()
		assertTrue(output.toString("UTF-8").contains("Original snapshot"))
		assertFalse(recreated.busy)
		assertNull(recreatedState.get<String>("pendingExport"))
		assertTrue(folder.root.resolve("transcript-exports").listFiles()!!.isEmpty())
	}
	@Test fun `restored interrupted write reports failure and allows retry`() {
		val context = mockk<Context>()
		every { context.cacheDir } returns folder.root
		val dir = folder.root.resolve("transcript-exports").apply { mkdirs() }
		dir.resolve("pending-test.txt").writeText("snapshot")
		val state = SavedStateHandle(mapOf("pendingExport" to "pending-test.txt", "writing" to true))
		val restored = TranscriptExportViewModel(context, state)
		assertFalse(restored.busy)
		assertTrue(restored.interrupted)
		assertNull(state.get<String>("pendingExport"))
		assertTrue(dir.listFiles()!!.isEmpty())
	}

}
