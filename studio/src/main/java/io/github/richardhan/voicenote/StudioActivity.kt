package io.github.richardhan.voicenote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
		enableEdgeToEdge()
		setContent {
			val dark = isSystemInDarkTheme()
			val scheme = if (dark) darkColorScheme(primary = Color(0xFFD6BDFF), surface = Color(0xFF17151C))
				else lightColorScheme(primary = Color(0xFF58308C), surface = Color(0xFFFAF8FC))
			MaterialTheme(colorScheme = scheme) {
				val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
					if (it) library.start() else library.message = R.string.permission
				}
				Studio(library, speech) {
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
		if (!isChangingConfigurations) {
			library.stopPlayback()
			library.finish()
		}
		super.onStop()
	}
}

@Composable
private fun Studio(library: RecordingLibrary, speech: SpeechConnection, toggle: () -> Unit) {
	val active = library.started != 0L
	val paused = library.isPaused
	val elapsed by produceState(0L, library.started, paused) {
		value = library.recordedDurationMs() / 1000
		while (active && !paused) {
			delay(250)
			value = library.recordedDurationMs() / 1000
		}
	}
	Surface(Modifier.fillMaxSize()) {
		LazyColumn(
			modifier = Modifier.safeDrawingPadding(),
			contentPadding = PaddingValues(20.dp),
			verticalArrangement = Arrangement.spacedBy(14.dp)
		) {
			item { Text(stringResource(R.string.studio), style = MaterialTheme.typography.headlineLarge) }
			item {
				Surface(color = Color(0xFF30213F), contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
					Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
						Text(stringResource(when {
							paused -> R.string.recording_paused
							active -> R.string.active
							else -> R.string.ready
						}))
						Text(String.format(Locale.ROOT, "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60),
							style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
						FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Button(onClick = toggle, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD1F4A1), contentColor = Color(0xFF21310A))) {
								Text(stringResource(if (active) R.string.stop else R.string.record))
							}
							if (active) {
								TextButton(onClick = { library.setPaused(!paused) },
									colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
									Text(stringResource(if (paused) R.string.resume_recording else R.string.pause_recording))
								}
							}
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
