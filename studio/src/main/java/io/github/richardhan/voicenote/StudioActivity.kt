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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

class StudioActivity : ComponentActivity() {
	private val library get() = (application as StudioApplication).library
	private val speakers: SpeakerAnalysis by viewModels()
	private val local: LocalSummary by viewModels()
	private val offline get() = (application as StudioApplication).offline
	private val speech: SpeechConnection by viewModels()
	private var destination by mutableStateOf<String?>(null)
	private var sharedSources by mutableStateOf(emptyList<Uri>())

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (savedInstanceState != null) {
			sharedSources = savedInstanceState.getStringArrayList("pendingImports")?.map(Uri::parse)
				?: listOfNotNull(savedInstanceState.getString("pendingImport")?.let(Uri::parse))
		} else if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) receiveShare(intent)
		setContent {
			StudioTheme { appearance, dark, choose ->
				var approveSlack by rememberSaveable { mutableStateOf(false) }
				var slackSession by rememberSaveable { mutableStateOf<String?>(null) }
				val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
					if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controlCapture(CaptureAction.START, slackSession)
					else library.message = R.string.permission
					slackSession = null
				}
				fun begin(generation: String?) {
					slackSession = generation
					val needed = buildList {
						if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
						if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
							!getPreferences(MODE_PRIVATE).getBoolean("askedNotification", false)) {
							add(Manifest.permission.POST_NOTIFICATIONS)
							getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotification", true).apply()
						}
					}
					if (needed.isEmpty()) { controlCapture(CaptureAction.START, generation); slackSession = null }
					else permission.launch(needed.toTypedArray())
				}
				if (approveSlack) AlertDialog(onDismissRequest = { approveSlack = false }, text = { Text(stringResource(R.string.slack_session)) },
					confirmButton = { TextButton(onClick = { approveSlack = false; begin(slackPrefs().getString("generation", null)) }) { Text(stringResource(R.string.slack_allow)) } },
					dismissButton = { TextButton(onClick = { approveSlack = false; begin(null) }) { Text(stringResource(R.string.record_local)) } })
				if (sharedSources.isNotEmpty()) key(sharedSources) { SharedImportDialog(sharedSources, library) { sharedSources = emptyList() } }
				Studio(library, speech, local, speakers, offline, dark, destination, { destination = null }, heading = { StudioHeading(appearance, choose) }) {
					if (library.started != 0L) controlCapture(CaptureAction.FINISH)
					else if (slackPrefs().getBoolean("live", false) && slackPrefs().contains("generation")) approveSlack = true
					else begin(null)
				}
			}
		}
	}
	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		receiveShare(intent)
	}

	private fun receiveShare(intent: Intent) {
		if (intent.action == OPEN_RECORDER || intent.action == OPEN_LIBRARY) {
			destination = intent.action
			return
		}
		if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "content") {
			sharedSources = listOfNotNull(intent.data)
			return
		}
		if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
		sharedSources = try { intent.sharedImportSources() } catch (_: BadParcelableException) { emptyList() }
		if (sharedSources.isEmpty()) library.message = R.string.import_error
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putStringArrayList("pendingImports", ArrayList(sharedSources.map(Uri::toString)))
	}

}

@Composable
private fun Studio(
	library: RecordingLibrary,
	speech: SpeechConnection,
	local: LocalSummary,
	speakers: SpeakerAnalysis,
	offline: OfflineSpeechModels,
	dark: Boolean,
	destination: String?,
	onNavigated: () -> Unit,
	heading: @Composable () -> Unit,
	toggle: () -> Unit
) {
	var input by rememberSaveable { mutableStateOf("") }
	val context = androidx.compose.ui.platform.LocalContext.current
	val addPhoto = rememberPhotoIntake(library)
	val revision = speech.revision + library.transcriptRevision
	val query = input.trim()
	var category by rememberSaveable { mutableStateOf("") }
	var favorites by rememberSaveable { mutableStateOf(false) }
	val visibleFiles = library.files.filter {
		(category.isEmpty() || library.catalog.value(it, "category") == category) &&
			(!favorites || library.catalog.value(it, "favorite").isNotEmpty())
	}
	val hits = transcriptHits(visibleFiles, query, revision)
	var selectedName by rememberSaveable(query) { mutableStateOf<String?>(null) }
	selectedName?.let { name ->
		library.files.firstOrNull { it.name == name }?.let { file ->
			MatchedTranscript(file, query, revision) { selectedName = null }
		}
	}
	val active = library.started != 0L
	val paused = library.isPaused
	val elapsed by produceState(0L, library.started, paused) {
		value = library.recordedDurationMs() / 1000
		while (active && !paused) {
			delay(250)
			value = library.recordedDurationMs() / 1000
		}
	}
	val list = androidx.compose.foundation.lazy.rememberLazyListState()
	LaunchedEffect(destination) {
		when (destination) {
			OPEN_RECORDER -> list.scrollToItem(0)
			OPEN_LIBRARY -> list.scrollToItem(6 + (if (library.message != 0) 1 else 0) + (if (speech.busy) 1 else 0))
			else -> return@LaunchedEffect
		}
		onNavigated()
	}
	Surface(Modifier.fillMaxSize()) {
		LazyColumn(
			state = list,
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
						if (!active) CaptureSettings(library, offline.installed)
						FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							Button(onClick = toggle, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF201917))) {
								Text(stringResource(if (active) R.string.stop else R.string.record))
							}
							if (active) {
								TextButton(onClick = library::captureBookmark, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
									Text(stringResource(R.string.bookmark) + " (" + library.bookmarkCount + ")")
								}
								TextButton(onClick = { context.controlCapture(if (paused) CaptureAction.RESUME else CaptureAction.PAUSE) },
									colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
									Text(stringResource(if (paused) R.string.resume_recording else R.string.pause_recording))
								}
							}
						}
						if (active && library.liveText.isNotBlank()) Text(library.liveText)
						library.captureTarget?.let { PhotoActions(library, it, addPhoto) }
						Text(stringResource(R.string.foreground), style = MaterialTheme.typography.bodySmall)
					}
				}
			}
			if (library.message != 0) item { Text(stringResource(library.message), color = MaterialTheme.colorScheme.error) }
			item { ConnectionPanel(speech) }
			item { SummaryModelPanel(local) }
			item { OfflineModelPanel(offline, speech.busy || library.recognizing.isNotEmpty()) }
			item { DrivePanel(); SlackPanel() }
			if (speech.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
			item { Text(stringResource(R.string.library), style = MaterialTheme.typography.titleLarge) }
			item { ImportActions(library) }
			item { LibraryFilters(library, category, { category = it }, favorites) { favorites = !favorites } }
			item {
				OutlinedTextField(
					value = input,
					onValueChange = {
						if (library.playingFile != null && it.isNotBlank()) library.stopPlayback()
						input = it
					},
					modifier = Modifier.fillMaxWidth(), singleLine = true,
					label = { Text(stringResource(R.string.search_transcripts)) },
					trailingIcon = if (input.isEmpty()) null else ({
						TextButton(onClick = { input = "" }) { Text(stringResource(R.string.clear_query)) }
					})
				)
			}
			when {
				query.isEmpty() -> {
					if (visibleFiles.isEmpty()) item { Text(stringResource(R.string.empty)) }
					items(visibleFiles, key = { it.name }) { file ->
						val recordedAt = library.catalog.title(file)
						Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
							Text(recordedAt, Modifier.weight(1f))
							if (file.extension in audioTypes) TextButton(onClick = { context.playRecording(file) }, enabled = !active) {
								Text(stringResource(if (library.playingFile == file) R.string.stop_playback else R.string.play))
							}
						}
						if (library.playingFile == file) PlaybackControls(library)
						RecordingActions(file, library)
						if (file.extension in audioTypes) PhotoActions(library, file, addPhoto)
						TrimAction(file, library)
						SpeakerAction(file, speakers, revision, library)
						if (file in library.recognizing) LinearProgressIndicator() else key(library.transcriptRevision) { TranscriptionRow(file, recordedAt, speech, local, offline) }
						HorizontalDivider()
					}
				}
				hits == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
				hits.isEmpty() -> item { Text(stringResource(R.string.no_matches)) }
				else -> items(hits, key = { it.recording.name }) { hit ->
					SearchResult(hit) { selectedName = hit.recording.name }
				}
			}
		}
	}
}
