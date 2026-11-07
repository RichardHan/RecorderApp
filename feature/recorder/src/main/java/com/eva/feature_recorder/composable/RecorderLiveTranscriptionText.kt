package com.eva.feature_recorder.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.eva.recorder.domain.recorder.LiveTranscriptState
import com.eva.recorder.domain.recorder.LiveTranscriptStatus
import kotlinx.coroutines.launch

/** Visible status line for the live transcript; pure so it can be unit tested. */
internal fun liveStatusText(state: LiveTranscriptState, isActive: Boolean): String = when {
	!isActive -> "按下錄音後，即時字幕會顯示在這裡"
	state.status == LiveTranscriptStatus.DISABLED -> "即時字幕未啟用或未選擇模型"
	state.status == LiveTranscriptStatus.LOADING -> "載入離線模型中…" + (state.modelLabel?.let { "（$it）" } ?: "")
	state.status == LiveTranscriptStatus.ERROR -> "⚠ " + (state.message ?: "即時字幕發生錯誤")
	state.status == LiveTranscriptStatus.DECODING -> "辨識中…"
	!state.hasText -> "聆聽中，說完一句、停頓後才會出字"
	else -> "聆聽中"
}

/** Persistent multi-line live transcript: all finalized lines, scrollable; auto-follows the newest line unless the user scrolled up. */
@Composable
internal fun RecorderLiveTranscriptionText(
	result: LiveTranscriptState,
	modifier: Modifier = Modifier,
	isActive: Boolean = true,
	fontFamily: FontFamily = FontFamily.Default,
) {
	val listState = rememberLazyListState()
	val scope = rememberCoroutineScope()
	val count = result.finalized.size + if (result.partial.isNotBlank()) 1 else 0
	val atBottom by remember {
		derivedStateOf {
			val info = listState.layoutInfo
			val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
			info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
		}
	}
	// auto-follow only while the user is at the bottom (does not yank them back while reading history)
	LaunchedEffect(count, result.partial) {
		if (count > 0 && atBottom) listState.animateScrollToItem(count - 1)
	}

	Surface(
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		shape = MaterialTheme.shapes.medium,
		modifier = modifier,
	) {
		Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
			Text(
				text = liveStatusText(result, isActive) +
					(result.message?.takeIf { result.status != LiveTranscriptStatus.ERROR }?.let { "\n⚠ $it" } ?: ""),
				style = MaterialTheme.typography.labelMedium,
				color = if (result.status == LiveTranscriptStatus.ERROR || result.message != null) MaterialTheme.colorScheme.error
				else MaterialTheme.colorScheme.onSurfaceVariant,
			)
			LazyColumn(
				state = listState,
				verticalArrangement = Arrangement.spacedBy(6.dp),
				modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
			) {
				itemsIndexed(result.finalized) { _, line ->
					Text(text = line, fontFamily = fontFamily, style = MaterialTheme.typography.bodyLarge)
				}
				if (result.partial.isNotBlank()) item {
					Text(
						text = result.partial,
						fontFamily = fontFamily,
						fontStyle = FontStyle.Italic,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						style = MaterialTheme.typography.bodyLarge,
					)
				}
			}
			if (!atBottom && count > 0) {
				TextButton(
					onClick = { scope.launch { listState.animateScrollToItem(count - 1) } },
					modifier = Modifier.align(Alignment.End),
				) { Text("跳到最新") }
			}
		}
	}
}
