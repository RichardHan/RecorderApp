package io.github.richardhan.voicenote

import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadedModelTest {
	private val instrumentation = InstrumentationRegistry.getInstrumentation()
	@get:Rule val folder = TemporaryFolder(instrumentation.targetContext.cacheDir)

	@Test fun offlineEnglishModelDecodesSpeechOnAndroid() = runBlocking {
		assumeTrue(InstrumentationRegistry.getArguments().getString("modelFixture") == "true")
		val context = object : ContextWrapper(instrumentation.targetContext) {
			override fun getNoBackupFilesDir(): File = folder.root
		}
		val model = speechModels(context).single { it.id == "vosk-model-small-en-us-0.15" }
		val root = model.directory(context).apply { mkdirs() }
		fun copyFixture(name: String, target: File) {
			val descriptor = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/studio-model-check/$name")
			ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> target.outputStream().use { input.copyTo(it) } }
		}
		copyFixture("english.zip", File(root, model.files.single().name))
		val audio = folder.newFile("speech.wav")
		copyFixture("speech.wav", audio)
		model.install(context) {}
		val words = model.decoder(context).use { it.words(decodeForSpeakers(audio)) }
		assertTrue("Recognizer returned: $words", words.contains("boy", ignoreCase = true) && words.split(' ').size >= 6)
	}
}
