package io.github.richardhan.voicenote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun MeetingWorkspace(library: RecordingLibrary, speech: SpeechConnection, offline: OfflineSpeechModels, local: LocalSummary, initialName: String?, close: () -> Unit) {
	BackHandler(onBack = close)
	var name by rememberSaveable { mutableStateOf<String?>(initialName) }
	var choose by remember { mutableStateOf(false) }
	val file = library.files.firstOrNull { it.name == name } ?: library.files.firstOrNull()
	var settings by rememberSaveable { mutableStateOf(false) }
	var imports by rememberSaveable { mutableStateOf(false) }
	val revision = speech.revision + library.transcriptRevision
	Surface(Modifier.fillMaxSize()) {
		LazyColumn(Modifier.safeDrawingPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			item {
				TextButton(onClick = close) { Text(stringResource(R.string.studio)) }
				Text(stringResource(R.string.meeting_workspace), style = MaterialTheme.typography.headlineMedium)
				Box {
					OutlinedButton(onClick = { choose = true }, enabled = library.files.isNotEmpty()) {
						Text(file?.let { library.catalog.title(it) } ?: stringResource(R.string.meeting_choose))
					}
					DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
						for (entry in library.files) DropdownMenuItem(text = { Text(library.catalog.title(entry)) }, onClick = { name = entry.name; choose = false })
					}
				}
				FlowRow {
					TextButton(onClick = { imports = !imports }) { Text(stringResource(R.string.meeting_import)) }
					TextButton(onClick = { settings = !settings }) { Text(stringResource(R.string.meeting_setup)) }
				}
				if (imports) { ImportActions(library); DeviceRecordings(library) }
				LibraryReview(library, speech, revision)
				if (settings) { UsagePanel(); ConnectionPanel(speech); OfflineModelPanel(offline, speech.busy); SummaryModelPanel(local) }
			}
			if (file == null) item {
				Text(stringResource(R.string.meeting_empty))
				for (tool in MeetingTool.entries) Text(stringResource(tool.label), style = MaterialTheme.typography.bodyLarge)
			} else item(key = file.name) {
				val transcript by produceState("", file, revision) { value = withContext(Dispatchers.IO) { readTranscript(file) } }
				library.catalog.value(file, "highlights").takeIf(String::isNotBlank)?.let {
					Text(stringResource(R.string.meeting_live_highlights), style = MaterialTheme.typography.titleMedium)
					SelectionContainer { Text(it) }
				}
				if (transcript.isBlank()) {
					Text(stringResource(R.string.meeting_need_transcript))
					if (!offline.installed) OfflineModelPanel(offline, speech.busy)
					if (!speech.valid()) ConnectionPanel(speech)
					TranscriptionRow(file, library.catalog.title(file), speech, local, offline)
				} else key(file, transcript) {
					MeetingEditor(file, transcript, speech, library)
					TranscriptChat(file, transcript, speech)
					SummaryAction(local, file, transcript)
					TranscriptTools(file, transcript, speech)
					var details by rememberSaveable { mutableStateOf(false) }
					TextButton(onClick = { details = !details }) { Text(stringResource(R.string.ai_transcript_versions)) }
					if (details) TranscriptionRow(file, library.catalog.title(file), speech, local, offline)
				}
			}
		}
	}
}

@Composable
private fun MeetingEditor(file: File, transcript: String, speech: SpeechConnection, library: RecordingLibrary) {
	val context = LocalContext.current
	val notes = remember(file, transcript) { MeetingNotes(file, transcript) }
	var tool by rememberSaveable { mutableStateOf(MeetingTool.Summary) }
	var translation by rememberSaveable { mutableStateOf("English") }
	var style by rememberSaveable { mutableIntStateOf(0) }
	var level by rememberSaveable { mutableIntStateOf(0) }
	var mask by rememberSaveable { mutableStateOf(false) }
	var confirm by rememberSaveable { mutableStateOf(false) }
	val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
	val styles = listOf(R.string.meeting_brief, R.string.meeting_minutes, R.string.meeting_bullets, R.string.ai_decision_format, R.string.ai_open_questions, R.string.custom)
	var custom by rememberSaveable { mutableStateOf(speech.defaultSummary) }
	val summary by produceState("", notes, speech.revision, library.transcriptRevision) { value = withContext(Dispatchers.IO) {
		notes.read(MeetingTool.Summary)?.points?.joinToString("\n") { it.text } ?: summaryFile(file, transcript).takeIf(File::isFile)?.readText().orEmpty()
	} }
	val contextMenu = tool.usesSavedSummary
	val levels = listOf(R.string.meeting_light, R.string.meeting_medium)
	val option = when (tool) {
		MeetingTool.Translation -> translation
		MeetingTool.Summary -> if (style == styles.lastIndex) custom else stringResource(styles[style])
		MeetingTool.Cleanup -> stringResource(levels[level])
		else -> ""
	}
	val passages by produceState(emptyList<MeetingPassage>(), file, transcript) { value = withContext(Dispatchers.IO) { meetingPassages(file, transcript) } }
	val tasks by produceState(emptyList<CheckedTask>(), notes, speech.revision) { value = withContext(Dispatchers.IO) { notes.tasks() } }
	FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
		for (entry in MeetingTool.entries) FilterChip(selected = tool == entry, onClick = { tool = entry }, label = { Text(stringResource(entry.label)) })
	}
	key(tool, if (tool == MeetingTool.Translation) option else "") {
		val result by produceState<MeetingOutput?>(null, notes, speech.revision) { value = withContext(Dispatchers.IO) { notes.read(tool, option) } }
		if (tool == MeetingTool.Translation) FlowRow {
			for (lang in listOf("English", "繁體中文", "简体中文", "日本語")) FilterChip(selected = lang == translation,
				onClick = { translation = lang }, label = { Text(lang) })
		}
		if (tool == MeetingTool.Summary || tool == MeetingTool.Cleanup) FlowRow {
			val summary = tool == MeetingTool.Summary
			for ((i, label) in (if (summary) styles else levels).withIndex()) FilterChip(
				selected = i == if (summary) style else level, onClick = { if (summary) style = i else level = i },
				label = { Text(stringResource(label)) })
		}
		if (tool == MeetingTool.Summary && style == styles.lastIndex) OutlinedTextField(custom, { custom = it }, label = { Text(stringResource(R.string.ai_custom_summary)) })
		if (tool == MeetingTool.Outline && result == null) {
			Text(stringResource(if (summary.isBlank()) R.string.ai_need_summary else R.string.ai_local_outline))
			SummaryOutline(summary)
		}
		if (tool == MeetingTool.Title) {
			val localTitle = summaryTitle(summary)
			if (localTitle.isEmpty()) Text(stringResource(R.string.ai_title_needs_summary))
			else {
				Text(localTitle)
				TextButton(onClick = { library.catalog.update(file, "title" to localTitle) }) { Text(stringResource(R.string.ai_summary_title)) }
			}
		}
		if (tool == MeetingTool.Decisions) {
			var decision by rememberSaveable { mutableStateOf("") }
			OutlinedTextField(decision, { decision = it }, label = { Text(stringResource(R.string.ai_manual_decision)) })
			TextButton(enabled = decision.isNotBlank() && !speech.busy, onClick = { speech.reviseMeetingPoint(file, transcript, tool, null, decision.trim()); decision = "" }) { Text(stringResource(R.string.done)) }
		}
		if (!speech.canAnalyze()) { Text(stringResource(R.string.meeting_needs_connection)); ConnectionPanel(speech) }
		Button(onClick = { confirm = true }, enabled = speech.canAnalyze() && !speech.busy && passages.isNotEmpty() && (!contextMenu || summary.isNotBlank())) { Text(stringResource(R.string.meeting_generate)) }
		if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(tool.label)) }, text = {
			Column {
				Text(stringResource(if (contextMenu) R.string.ai_outline_disclose else R.string.meeting_disclose, speech.textDestination, speech.chatModel))
				if (tool == MeetingTool.Summary && speech.summaryNotes) Text(stringResource(R.string.ai_use_agenda))
				Text(stringResource(R.string.receipt_requests, if (contextMenu) 1 else meetingBatches(tool, passages).size))
				FilterChip(selected = mask, onClick = { mask = !mask }, label = { Text(stringResource(R.string.mask_contacts)) })
			}
		}, confirmButton = { TextButton(onClick = { confirm = false; speech.generateMeetingNotes(file, transcript, tool, option, language, mask) }) { Text(stringResource(R.string.meeting_generate)) } },
			dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
		if (speech.busy) { LinearProgressIndicator(); TextButton(onClick = speech::cancel) { Text(stringResource(R.string.cancel)) } }
		if (speech.error != 0) Text(if (speech.error == R.string.http_error) stringResource(speech.error, speech.httpStatus) else stringResource(speech.error))
		result?.let { output ->
			if (tool == MeetingTool.Outline) CopyOutline(meetingDocument(tool, output, passages))
			if (output.points.isNotEmpty() && passages.isNotEmpty()) ExportMeetingResult(file, tool, output, passages)
			if (tool == MeetingTool.Summary) SummaryVersions(notes, speech.revision)
			if (tool == MeetingTool.Outline) TextButton(enabled = !speech.busy, onClick = { speech.discardResult(file, transcript, tool, option) }) { Text(stringResource(R.string.ai_restore_outline)) }
			if (tool == MeetingTool.Cleanup || tool == MeetingTool.Translation) DiscardAction { speech.discardResult(file, transcript, tool, option) }
			if (output.option.isNotEmpty()) Text(output.option, style = MaterialTheme.typography.labelLarge)
			if (output.points.isEmpty()) Text(stringResource(R.string.meeting_no_items))
			val groups = if (tool == MeetingTool.Outline) output.points.groupBy { it.group } else mapOf("" to output.points)
			for ((group, points) in groups) {
				var expanded by remember(group) { mutableStateOf(true) }
				if (group.isNotEmpty()) TextButton(onClick = { expanded = !expanded }) { Text(group) }
				if (expanded) for (point in points) {
					val source = point.source?.let(passages::getOrNull)
					if (tool == MeetingTool.Translation && source != null) Text(source.text, style = MaterialTheme.typography.bodySmall)
					SelectionContainer { Text(point.text) }
					if (tool == MeetingTool.Decisions || tool == MeetingTool.Chapters) {
						var editing by remember(point) { mutableStateOf(false) }
						if (editing) {
							var draft by remember(point) { mutableStateOf(point.text) }
							OutlinedTextField(draft, { draft = it })
							FlowRow {
								TextButton(enabled = !speech.busy && draft.isNotBlank(), onClick = {
									speech.reviseMeetingPoint(file, transcript, tool, point, draft.trim())
									editing = false
								}) { Text(stringResource(R.string.done)) }
								TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
							}
						} else FlowRow {
							TextButton(onClick = { editing = true }) { Text(stringResource(R.string.meeting_edit)) }
							TextButton(enabled = !speech.busy, onClick = { speech.reviseMeetingPoint(file, transcript, tool, point, null) }) { Text(stringResource(R.string.meeting_remove)) }
						}
					}
					if (source != null && point.quote.isNotBlank() && tool != MeetingTool.Translation) Text(point.quote, style = MaterialTheme.typography.bodySmall)
					source?.ms?.let { ms -> TextButton(enabled = library.started == 0L && file.extension in audioTypes, onClick = { context.playRecording(file, ms) }) { Text(formatClipTime(ms.toInt())) } }
					if (tool == MeetingTool.Actions && tasks.none { it.text == point.text }) TextButton(enabled = !speech.busy, onClick = {
						speech.changeMeetingTasks(file, transcript) { it + CheckedTask(point.text, false) }
					}) { Text(stringResource(R.string.meeting_add)) }
					if (tool == MeetingTool.Title) TextButton(onClick = { library.catalog.update(file, "title" to point.text) }) { Text(stringResource(R.string.meeting_apply_title)) }
					HorizontalDivider()
				}
			}
		}
	}
	if (tool == MeetingTool.Actions) {
		val highlights = library.catalog.value(file, "highlights").ifBlank { summary }
		var chooseHighlights by rememberSaveable { mutableStateOf(false) }
		if (highlights.isNotBlank()) TextButton(onClick = { chooseHighlights = !chooseHighlights }) { Text(stringResource(R.string.ai_tasks_highlights)) }
		if (chooseHighlights) for (line in highlights.lines().map { it.trim().trimStart('-', '*', '•', '#', ' ') }.filter(String::isNotBlank).distinct()) {
			TextButton(enabled = !speech.busy && tasks.none { it.text == line }, onClick = { speech.changeMeetingTasks(file, transcript) { it + CheckedTask(line, false) } }) { Text(line) }
		}
		val remaining = tasks.filterNot { it.done }.joinToString("\n") { "☐ " + it.text }
		if (remaining.isNotBlank()) TextButton(onClick = {
			context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", remaining))
		}) { Text(stringResource(R.string.ai_copy_open)) }
		var draft by rememberSaveable { mutableStateOf("") }
		OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(R.string.meeting_new_task)) })
		TextButton(enabled = draft.isNotBlank() && !speech.busy, onClick = {
			val item = CheckedTask(draft.trim(), false)
			speech.changeMeetingTasks(file, transcript) { it + item }
			draft = ""
		}) { Text(stringResource(R.string.meeting_add)) }
		for (task in tasks) Row {
			Checkbox(task.done, enabled = !speech.busy, onCheckedChange = { done ->
				speech.changeMeetingTasks(file, transcript) { rows -> rows.map { if (it.text == task.text) it.copy(done = done) else it } }
			})
			Text(task.text, Modifier.weight(1f))
			TextButton(enabled = !speech.busy, onClick = { speech.changeMeetingTasks(file, transcript) { rows -> rows.filterNot { it.text == task.text } } }) { Text(stringResource(R.string.meeting_remove)) }
		}
	}
	FollowUpDraft(file, transcript, notes, tasks, speech)
}

@Composable
private fun ExportMeetingResult(file: File, tool: MeetingTool, output: MeetingOutput, passages: List<MeetingPassage>) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var failed by remember { mutableStateOf(false) }
	val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
		if (uri != null) scope.launch {
			try {
				withContext(Dispatchers.IO) {
					requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
						it.write(meetingDocument(tool, output, passages))
					}
				}
			} catch (e: CancellationException) { throw e }
			catch (_: Exception) { failed = true }
		}
	}
	TextButton(onClick = { failed = false; save.launch(file.nameWithoutExtension + "-" + tool.name + ".md") }) { Text(stringResource(R.string.meeting_export)) }
	if (failed) Text(stringResource(R.string.document_error))
}

@Composable
private fun FollowUpDraft(file: File, transcript: String, notes: MeetingNotes, tasks: List<CheckedTask>, speech: SpeechConnection) {
	val context = LocalContext.current
	val summary by produceState("", notes, speech.revision) { value = withContext(Dispatchers.IO) {
		notes.read(MeetingTool.Summary)?.points?.joinToString("\n") { it.text }
			?: summaryFile(file, transcript).takeIf(File::isFile)?.readText().orEmpty()
	} }
	val saved by produceState<Pair<String, String>?>(null, notes, speech.revision) { value = withContext(Dispatchers.IO) { notes.draft() } }
	val material = listOf(summary, tasks.joinToString("\n") { (if (it.done) "[x] " else "[ ] ") + it.text })
		.filter(String::isNotBlank).joinToString("\n\n")
	val digest = remember(material) { transcriptDigest(material).toHexString() }
	var open by rememberSaveable { mutableStateOf(false) }
	TextButton(enabled = material.isNotBlank() || saved != null, onClick = { open = true }) { Text(stringResource(R.string.prompt_follow_up)) }
	if (!open) return
	var draft by rememberSaveable(saved) { mutableStateOf(saved?.first ?: material) }
	var instructions by rememberSaveable { mutableStateOf("") }
	var confirm by rememberSaveable { mutableStateOf(false) }
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.prompt_follow_up)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			if (saved != null) DiscardAction { speech.discardResult(file, transcript, null, ""); open = false }
			if (saved != null && saved?.second != digest) Text(stringResource(R.string.meeting_draft_stale))
			OutlinedTextField(draft, { draft = it }, minLines = 3)
			OutlinedTextField(instructions, { instructions = it }, label = { Text(stringResource(R.string.meeting_format)) })
			TextButton(enabled = material.isNotBlank() && speech.canAnalyze() && !speech.busy, onClick = { confirm = true }) { Text(stringResource(R.string.meeting_generate)) }
			FlowRow {
				TextButton(enabled = draft.isNotBlank(), onClick = { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", draft)) }) { Text(stringResource(R.string.meeting_copy)) }
				TextButton(enabled = draft.isNotBlank(), onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, draft), null)) }) { Text(stringResource(R.string.meeting_share)) }
			}
			if (speech.busy) LinearProgressIndicator()
			if (speech.error != 0) Text(if (speech.error == R.string.http_error) stringResource(speech.error, speech.httpStatus) else stringResource(speech.error))
		}
	}, confirmButton = { TextButton(enabled = draft.isNotBlank() && !speech.busy, onClick = { speech.editDraft(file, transcript, draft, saved?.second ?: digest); open = false }) { Text(stringResource(R.string.done)) } },
		dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } })
	if (confirm) AlertDialog(onDismissRequest = { confirm = false }, text = { Text(stringResource(R.string.meeting_draft_disclose, speech.textDestination, speech.chatModel, tasks.size)) },
		confirmButton = { TextButton(onClick = { confirm = false; speech.followUp(file, transcript, material, instructions) }) { Text(stringResource(R.string.meeting_generate)) } },
		dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}


@Composable
private fun CopyOutline(text: String) {
	val context = LocalContext.current
	if (text.isNotBlank()) TextButton(onClick = {
		context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
	}) { Text(stringResource(R.string.ai_copy_outline)) }
}

@Composable
private fun SummaryOutline(summary: String) {
	val collapsed = remember(summary) { mutableStateListOf<Int>() }
	val headings = mutableListOf<Pair<Int, Int>>()
	for ((index, raw) in summary.lines().withIndex()) {
		val line = raw.trim()
		if (line.isEmpty()) continue
		val hashes = line.takeWhile { it == '#' }.length
		val depth = when {
			hashes > 0 && line.getOrNull(hashes) == ' ' -> hashes
			line.endsWith(':') || line.endsWith('：') -> 1
			else -> 0
		}
		if (depth > 0) {
			while (headings.isNotEmpty() && headings.last().first >= depth) headings.removeAt(headings.lastIndex)
			if (headings.none { it.second in collapsed }) TextButton(
				modifier = Modifier.padding(start = (headings.size * 12).dp),
				onClick = { if (index in collapsed) collapsed.remove(index) else collapsed.add(index) }
			) { Text(line.trimStart('#', ' ')) }
			headings.add(depth to index)
		} else if (headings.none { it.second in collapsed }) {
			Text("• " + line.trimStart('-', '*', '•', ' '), Modifier.padding(start = (headings.size.coerceAtMost(6) * 12).dp))
		}
	}
	CopyOutline(summary)
}
