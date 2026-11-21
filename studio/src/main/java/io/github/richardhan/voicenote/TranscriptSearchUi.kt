package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun transcriptHits(files: List<File>, query: String, revision: Int): List<TranscriptHit>? {
	return key(files, query, revision) {
		val results by produceState<List<TranscriptHit>?>(null) {
			if (query.isEmpty()) return@produceState
			delay(180)
			value = withContext(Dispatchers.IO) {
				files.mapNotNull { recording ->
					currentCoroutineContext().ensureActive()
					findExcerpt(readTranscript(recording), query)?.let { TranscriptHit(recording, it) }
				}
			}
		}
		results
	}
}

@Composable
internal fun SearchResult(hit: TranscriptHit, onClick: () -> Unit) {
	OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Text(DateFormat.getDateTimeInstance().format(Date(hit.recording.lastModified())), style = MaterialTheme.typography.labelLarge)
			Text(hit.excerpt)
		}
	}
}

@Composable
internal fun MatchedTranscript(file: File, query: String, revision: Int, close: () -> Unit) {
	val fullText by produceState<String?>(null, file, revision) {
		value = withContext(Dispatchers.IO) { readTranscript(file) }
	}
	AlertDialog(
		onDismissRequest = close,
		title = { Text(stringResource(R.string.transcript)) },
		text = {
			val text = fullText
			if (text == null) {
				LinearProgressIndicator()
			} else {
				val index = remember(text, query) { text.indexOf(query, ignoreCase = true) }
				if (index < 0) Text(stringResource(R.string.no_matches))
				else key(text, index) {
					// Keep the match in a single block even if a long paragraph crosses a page boundary.
					val start = (index - 50).coerceAtLeast(0)
					val end = (index + query.length + 350).coerceAtMost(text.length)
					val before = remember(text, start) { text.substring(0, start).chunked(512) }
					val after = remember(text, end) { text.substring(end).chunked(512) }
					val state = rememberLazyListState(initialFirstVisibleItemIndex = before.size)
					LazyColumn(Modifier.heightIn(max = 400.dp), state = state) {
						items(before.size) { Text(before[it]) }
						item {
							val style = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primaryContainer,
								color = MaterialTheme.colorScheme.onPrimaryContainer)
							Text(buildAnnotatedString {
								append(text.substring(start, end))
								addStyle(style, index - start, index - start + query.length)
							})
						}
						items(after.size) { Text(after[it]) }
					}
				}
			}
		},
		confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.done)) } }
	)
}
