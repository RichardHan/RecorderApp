package io.github.richardhan.voicenote

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedAudioIntentTest {
	private val first = Uri.parse("content://files/meeting")
	private val second = Uri.parse("content://files/interview")

	private fun multiple() = Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/*")

	@Test fun streamExtrasTakePrecedenceAndRepeatedUrisAreImportedOnce() {
		val intent = multiple().apply {
			putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second, first))
			clipData = ClipData.newRawUri(null, second)
		}
		assertEquals(listOf(first, second), intent.sharedImportSources())
	}

	@Test fun clipDataAlonePreservesSelectionOrder() {
		val intent = multiple().apply {
			clipData = ClipData.newRawUri(null, second).apply { addItem(ClipData.Item(first)) }
		}
		assertEquals(listOf(second, first), intent.sharedImportSources())
	}

	@Test fun missingOrNonContentUrisAreRejectedBeforeConfirmation() {
		val intent = multiple()
		assertTrue(intent.sharedImportSources().isEmpty())
		intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, Uri.parse("file:///private/test")))
		assertTrue(intent.sharedImportSources().isEmpty())
		intent.removeExtra(Intent.EXTRA_STREAM)
		intent.clipData = ClipData.newRawUri(null, first).apply { addItem(ClipData.Item("caption")) }
		assertTrue(intent.sharedImportSources().isEmpty())
	}

	@Test fun multipleIsAudioOnlyAndSingleLinksStillWork() {
		val multi = multiple().apply {
			type = "text/plain"
			putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
		}
		assertTrue(multi.sharedImportSources().isEmpty())
		val url = "https://example.org/audio.mp3"
		val single = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Meeting $url")
		assertEquals(listOf(Uri.parse(url)), single.sharedImportSources())
	}
}
