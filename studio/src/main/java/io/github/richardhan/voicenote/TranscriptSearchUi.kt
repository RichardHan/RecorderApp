package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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

private data class MatchedText(val text: String, val offsets: List<Int>)

@Composable
internal fun MatchedTranscript(file: File, query: String, revision: Int, close: () -> Unit) {
	key(file, query, revision) {
		val result by produceState<MatchedText?>(null) {
			value = withContext(Dispatchers.IO) {
				val text = readTranscript(file)
				MatchedText(text, transcriptMatchOffsets(text, query))
			}
		}
		var position by rememberSaveable { mutableIntStateOf(0) }
		AlertDialog(
			onDismissRequest = close,
			title = { Text(stringResource(R.string.transcript)) },
			text = {
				val matches = result
				when {
					matches == null -> LinearProgressIndicator()
					matches.offsets.isEmpty() -> Text(stringResource(R.string.no_matches))
					else -> {
						val last = matches.offsets.lastIndex
						val current = position.coerceIn(0, last)
						Column {
							Text(stringResource(R.string.match_position, current + 1, last + 1),
								modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
								style = MaterialTheme.typography.labelLarge)
							if (last > 0) {
								Row {
									TextButton(onClick = { position = current - 1 }, enabled = current > 0) {
										Text(stringResource(R.string.previous_match))
									}
									TextButton(onClick = { position = current + 1 }, enabled = current < last) {
										Text(stringResource(R.string.next_match))
									}
								}
							}
							key(current) { MatchedPassage(matches.text, matches.offsets[current], query.length) }
						}
					}
				}
			},
			confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.done)) } }
		)
	}
}

private const val PAGE_SIZE = 512

// Keep emoji and other supplementary characters intact across text blocks.
private fun String.pageBoundary(index: Int): Int =
	if (index in 1 until length && this[index].isLowSurrogate() && this[index - 1].isHighSurrogate()) index + 1 else index

@Composable
private fun MatchedPassage(text: String, offset: Int, queryLength: Int) {
	// A short prefix on the same line keeps the selected occurrence in view even after many blank lines.
	val prefix = text.substring((offset - 50).coerceAtLeast(0), offset).substringAfterLast('\n')
	val start = text.pageBoundary(offset - prefix.length)
	val end = text.pageBoundary((offset + queryLength + 350).coerceAtMost(text.length))
	val before = start / PAGE_SIZE + if (start % PAGE_SIZE == 0) 0 else 1
	val remaining = text.length - end
	val after = remaining / PAGE_SIZE + if (remaining % PAGE_SIZE == 0) 0 else 1
	val state = rememberLazyListState(initialFirstVisibleItemIndex = before)
	val highlight = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primaryContainer,
		color = MaterialTheme.colorScheme.onPrimaryContainer)
	LazyColumn(Modifier.heightIn(max = 400.dp), state = state) {
		items(before) { page ->
			Text(text.substring(text.pageBoundary(page * PAGE_SIZE), text.pageBoundary(minOf((page + 1) * PAGE_SIZE, start))))
		}
		item {
			Text(buildAnnotatedString {
				append(text.substring(start, end))
				addStyle(highlight, offset - start, offset - start + queryLength)
			})
		}
		items(after) { page ->
			val from = end + page * PAGE_SIZE
			Text(text.substring(text.pageBoundary(from), text.pageBoundary(minOf(from + PAGE_SIZE, text.length))))
		}
	}
}
