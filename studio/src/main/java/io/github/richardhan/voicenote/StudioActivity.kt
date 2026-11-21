package io.github.richardhan.voicenote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class StudioActivity : ComponentActivity() {
	private val library: RecordingLibrary by viewModels()
	private val speech: SpeechConnection by viewModels()
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			StudioTheme { appearance, dark, choose ->
				val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
					if (it) library.start() else library.message = R.string.permission
				}
				Studio(library, speech, dark, heading = { StudioHeading(appearance, choose) }) {
					when {
						library.started != 0L -> library.finish()
						checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> library.start()
						else -> permission.launch(Manifest.permission.RECORD_AUDIO)
					}
				}
			}
		}
	}
	override fun onStop() {
		if (!isChangingConfigurations) library.finish()
		super.onStop()
	}
}

@Composable
private fun Studio(
	library: RecordingLibrary,
	speech: SpeechConnection,
	dark: Boolean,
	heading: @Composable () -> Unit,
	toggle: () -> Unit
) {
	val active = library.started != 0L
	val elapsed by produceState(0L, library.started) {
		value = 0
		while (active) {
			value = (SystemClock.elapsedRealtime() - library.started) / 1000
			delay(250)
		}
	}
	Surface(Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.safeDrawingPadding(),
			contentPadding = PaddingValues(20.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
			item { heading() }
			item {
				val panel = if (dark) MaterialTheme.colorScheme.surfaceContainer else Color(0xFF30213F)
				val accent = if (dark) MaterialTheme.colorScheme.primary else Color(0xFFD1F4A1)
				Surface(color = panel, contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
					Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
						Text(stringResource(if (active) R.string.active else R.string.ready))
						Text(String.format(Locale.ROOT, "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60),
							style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
						LiveWaveform(library, accent)
						Button(onClick = toggle, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF201917))) {
							Text(stringResource(if (active) R.string.stop else R.string.record))
						}
						Text(stringResource(R.string.foreground), style = MaterialTheme.typography.bodySmall)
					}
				}
			}
			if (library.message != 0) item { Text(stringResource(library.message), color = MaterialTheme.colorScheme.error) }
			item { ConnectionPanel(speech) }
			if (speech.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
			item { Text(stringResource(R.string.library), style = MaterialTheme.typography.titleLarge) }
			if (library.files.isEmpty()) item { Text(stringResource(R.string.empty)) }
			items(library.files, key = { it.name }) { file ->
				Text(DateFormat.getDateTimeInstance().format(Date(file.lastModified())))
				TranscriptionRow(file, speech)
				HorizontalDivider()
			}
		}
	}
}
