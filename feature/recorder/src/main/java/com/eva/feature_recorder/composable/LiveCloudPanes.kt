package com.eva.feature_recorder.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.live.LiveCloudPhase
import com.eva.cloudtranscribe.live.LiveCloudState
import com.eva.recorder.domain.recorder.LiveTranscriptState

internal fun cloudStatusText(s: LiveCloudState): String = buildString {
	append(
		when (s.phase) {
			LiveCloudPhase.OFF -> "進階雲端模式未啟用"
			LiveCloudPhase.WAITING_FIRST_CHUNK -> "累積第一段音訊中（預設 60 秒後送出）"
			LiveCloudPhase.TRANSCRIBING -> "雲端轉錄中…"
			LiveCloudPhase.IDLE -> "等待下一段"
			LiveCloudPhase.SUMMARIZING -> "整理重點中…"
			LiveCloudPhase.FINISHING -> "停止中：送出最後一段並整理最終重點…"
			LiveCloudPhase.DONE -> "已結束"
		}
	)
	if (s.pendingChunks > 0) append("・待送 ${s.pendingChunks} 段")
	s.transcribeModel?.let { append("・轉錄 $it") }
	if (s.skippedMs > 0) append("\n⚠ 雲端跟不上，約 ${s.skippedMs / 1000} 秒未上傳（錄音完整保存）")
	s.transcribeError?.let { append("\n⚠ 轉錄：$it") }
}

/** Separate panes: on-device live captions | cloud transcript (+ provisional offline) | key points. */
@Composable
internal fun LiveTranscriptPanes(
	offline: LiveTranscriptState,
	cloud: LiveCloudState,
	isActive: Boolean,
	showOffline: Boolean,
	modifier: Modifier = Modifier,
	fontFamily: FontFamily = FontFamily.Default,
) {
	val tabs = buildList { if (showOffline) add("即時字幕"); add("雲端稿"); add("重點") }
	var tab by rememberSaveable { mutableIntStateOf(0) }
	val current = tabs.getOrElse(tab) { tabs.first() }
	Column(modifier) {
		val paneModifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 4.dp)
		when (current) {
			"即時字幕" -> RecorderLiveTranscriptionText(offline, paneModifier, isActive, fontFamily)
			"雲端稿" -> CloudTranscriptPane(cloud, paneModifier, fontFamily)
			else -> KeyPointsPane(cloud, paneModifier)
		}
		// view switcher at the bottom, just above the recorder controls (user request)
		SecondaryTabRow(selectedTabIndex = tabs.indexOf(current)) {
			tabs.forEachIndexed { i, t -> Tab(selected = t == current, onClick = { tab = i }, text = { Text(t) }) }
		}

	}
}

@Composable
private fun CloudTranscriptPane(cloud: LiveCloudState, modifier: Modifier, fontFamily: FontFamily) {
	val listState = rememberLazyListState()
	val total = cloud.cloudSpans.size + cloud.provisional.size
	LaunchedEffect(total) { if (total > 0) listState.animateScrollToItem(total - 1) }
	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.fillMaxSize().padding(12.dp)) {
			Text(cloudStatusText(cloud), style = MaterialTheme.typography.labelMedium,
				color = if (cloud.transcribeError != null || cloud.skippedMs > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
			LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f).padding(top = 8.dp)) {
				items(cloud.cloudSpans) { sp ->
					val t = "%d:%02d".format(sp.startMs / 60_000, sp.startMs / 1000 % 60)
					Text(
						text = if (sp.ok) "$t  ${sp.text.ifBlank { "（無語音）" }}" else "$t  ⚠ ${sp.error ?: "失敗"}",
						fontFamily = fontFamily, style = MaterialTheme.typography.bodyMedium,
						color = if (sp.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
					)
				}
				items(cloud.provisional) { l ->
					Text("暫定（離線）  ${l.text}", fontFamily = fontFamily, fontStyle = FontStyle.Italic,
						style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}

@Composable
private fun KeyPointsPane(cloud: LiveCloudState, modifier: Modifier) {
	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.fillMaxSize().padding(12.dp)) {
			Text(
				buildString {
					append(cloud.summaryModel?.let { "重點整理：$it" } ?: "重點整理")
					cloud.keyPointsAtMs?.let { append("・更新於錄音 %d:%02d".format(it / 60_000, it / 1000 % 60)) }
					if (cloud.keyPoints.isBlank()) append("\n首次整理約在錄音 1:30，且需先完成一段雲端轉錄")
					cloud.summaryError?.let { append("\n⚠ 最近一次整理失敗，保留上次結果：$it") }
				},
				style = MaterialTheme.typography.labelMedium,
				color = if (cloud.summaryError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
			)
			SelectionContainer(Modifier.weight(1f).padding(top = 8.dp)) {
				Text(cloud.keyPoints, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.verticalScroll(rememberScrollState()))
			}
		}
	}
}
