package io.github.richardhan.voicenote

import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
	private val local: LocalSummary by viewModels()
	private val speech: SpeechConnection by viewModels()
	private var sharedSources by mutableStateOf(emptyList<Uri>())

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (savedInstanceState != null) {
			sharedSources = savedInstanceState.getStringArrayList("pendingImports")?.map(Uri::parse)
				?: listOfNotNull(savedInstanceState.getString("pendingImport")?.let(Uri::parse))
		} else if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) receiveShare(intent)
		setContent {
			StudioTheme { appearance, dark, choose ->
				val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
					if (it) library.start() else library.message = R.string.permission
				}
				if (sharedSources.isNotEmpty()) key(sharedSources) { SharedImportDialog(sharedSources, library) { sharedSources = emptyList() } }
				Studio(library, speech, local, dark, heading = { StudioHeading(appearance, choose) }) {
					when {
						library.started != 0L -> library.finish()
						checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> library.start()
						else -> permission.launch(Manifest.permission.RECORD_AUDIO)
					}
				}
			}
		}
	}
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		receiveShare(intent)
	}

	private fun receiveShare(intent: Intent) {
		if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
		sharedSources = try { intent.sharedImportSources() } catch (_: BadParcelableException) { emptyList() }
		if (sharedSources.isEmpty()) library.message = R.string.import_error
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putStringArrayList("pendingImports", ArrayList(sharedSources.map(Uri::toString)))
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
private fun Studio(
	library: RecordingLibrary,
	speech: SpeechConnection,
	local: LocalSummary,
	dark: Boolean,
	heading: @Composable () -> Unit,
	toggle: () -> Unit
) {
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
			item { heading() }
			item {
				val panel = if (dark) MaterialTheme.colorScheme.surfaceContainer else Color(0xFF30213F)
				val accent = if (dark) MaterialTheme.colorScheme.primary else Color(0xFFD1F4A1)
				Surface(color = panel, contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
					Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
						Text(stringResource(when {
							paused -> R.string.recording_paused
							active -> R.string.active
							else -> R.string.ready
						}))
						Text(String.format(Locale.ROOT, "%02d:%02d:%02d", elapsed / 3600, elapsed / 60 % 60, elapsed % 60),
							style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
						LiveWaveform(library, accent)
						FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Button(onClick = toggle, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF201917))) {
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
			item { SummaryModelPanel(local) }
			if (speech.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
			item { Text(stringResource(R.string.library), style = MaterialTheme.typography.titleLarge) }
			item { ImportActions(library) }
			if (library.files.isEmpty()) item { Text(stringResource(R.string.empty)) }
			items(library.files, key = { it.name }) { file ->
				val recordedAt = DateFormat.getDateTimeInstance().format(Date(file.lastModified()))
				Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
					Text(recordedAt, Modifier.weight(1f))
					if (file.extension in audioTypes) TextButton(onClick = { library.togglePlayback(file) }, enabled = !active) {
						Text(stringResource(if (library.playingFile == file) R.string.stop_playback else R.string.play))
					}
				}
				TranscriptionRow(file, recordedAt, speech, local)
				HorizontalDivider()
			}
		}
	}
}
