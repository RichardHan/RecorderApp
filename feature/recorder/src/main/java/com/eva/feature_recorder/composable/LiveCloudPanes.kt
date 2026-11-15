package com.eva.feature_recorder.composable

import com.eva.cloudtranscribe.live.MergedLine.Kind as K
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
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
			LiveCloudPhase.OFF -> "雲端模式未啟用"
			LiveCloudPhase.WAITING_FIRST_CHUNK, LiveCloudPhase.IDLE -> "聆聽中…"
			LiveCloudPhase.TRANSCRIBING -> "雲端轉錄中…"
			LiveCloudPhase.SUMMARIZING -> "整理重點中…"
			LiveCloudPhase.FINISHING -> "整理最終重點…"
			LiveCloudPhase.DONE -> "已結束"
		}
	)
	if (s.skippedMs > 0) append("\n⚠ 約 ${s.skippedMs / 1000} 秒未上傳雲端（錄音完整保存）")
	s.transcribeError?.let { append("\n⚠ 轉錄：$it") }
}

/**
 * Two areas: top = merged transcript (latest offline captions, replaced by cloud text only where cloud covered the
 * audio) with a short "雲端已更新" note; bottom = key points. No tabs, no third panel.
 */
@Composable
internal fun LiveTranscriptPanes(
	offline: LiveTranscriptState,
	cloud: LiveCloudState,
	isActive: Boolean,
	showOffline: Boolean,
	modifier: Modifier = Modifier,
	fontFamily: FontFamily = FontFamily.Default,
) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
		SectionTitle("逐字稿")
		MergedTranscriptPane(offline, cloud, isActive, showOffline, Modifier.fillMaxWidth().weight(1.4f), fontFamily)
		SectionTitle("重點")
		KeyPointsPane(cloud, Modifier.fillMaxWidth().weight(1f))
	}
}

@Composable
private fun MergedTranscriptPane(
	offline: LiveTranscriptState,
	cloud: LiveCloudState,
	isActive: Boolean,
	showOffline: Boolean,
	modifier: Modifier,
	fontFamily: FontFamily,
) {
	val merged = remember(cloud.cloudSpans, cloud.offlineTimed, offline.finalized) {
		com.eva.cloudtranscribe.live.LiveMerge.merge(cloud.cloudSpans, cloud.offlineTimed, offline.finalized)
	}
	val listState = rememberLazyListState()
	val total = merged.size + if (offline.partial.isNotBlank()) 1 else 0
	val atBottom by remember { derivedStateOf { val i = listState.layoutInfo; (i.visibleItemsInfo.lastOrNull()?.index ?: -1) >= i.totalItemsCount - 2 } }
	LaunchedEffect(total) { if (total > 0 && atBottom) listState.animateScrollToItem(total - 1) }
	Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = modifier) {
		Column(Modifier.fillMaxSize().padding(10.dp)) {
			Text(
				buildString {
					append(if (showOffline) liveStatusText(offline, isActive) else "即時字幕已關閉")
					val cloudLine = cloudStatusText(cloud).lineSequence().first()
					if (cloudLine.isNotBlank() && cloudLine != "聆聽中…") append("・").append(cloudLine)
					if (cloud.skippedMs > 0) append("\n⚠ 約 ${cloud.skippedMs / 1000} 秒未上傳雲端（錄音完整保存）")
					cloud.transcribeError?.let { append("\n⚠ 雲端：$it") }
					offline.message?.let { append("\n⚠ 離線：$it") }
				},
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.weight(1f).padding(top = 6.dp)) {
				items(merged) { raw ->
					// bounded render: a single runaway line can never flood the screen
					val line = if (raw.text.length > 500) raw.copy(text = raw.text.take(500) + "…（已截斷顯示）") else raw
					val t = line.startMs?.let { "%d:%02d ".format(it / 60_000, it / 1000 % 60) } ?: ""
					when (line.kind) {
						K.CLOUD -> Text("$t☁ ${line.text}", fontFamily = fontFamily, style = MaterialTheme.typography.bodyMedium)
						K.OFFLINE_PROVISIONAL -> Text("${t}暫定 ${line.text}", fontFamily = fontFamily, fontStyle = FontStyle.Italic,
							style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
						K.OFFLINE_UNALIGNED -> Text("離線（無時間戳，未對齊）${line.text}", fontFamily = fontFamily, fontStyle = FontStyle.Italic,
							style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						K.CLOUD_FAILED -> Text("$t⚠ 雲端：${line.text}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
						K.CLOUD_SUSPICIOUS -> Text("$t⚠ 雲端疑似重複幻覺，未採用（原文保留）：${line.text}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
						K.OFFLINE_SUSPICIOUS -> Text("$t⚠ 離線疑似重複輸出，未送重點：${line.text}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
					}
				}
				if (offline.partial.isNotBlank()) item {
					Text(offline.partial, fontFamily = fontFamily, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}

@Composable
private fun SectionTitle(text: String) =
	Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun CloudTranscriptPane(cloud: LiveCloudState, modifier: Modifier, fontFamily: FontFamily) {
	val listState = rememberLazyListState()
	val total = cloud.cloudSpans.size + cloud.provisional.size
	val atBottom by remember { derivedStateOf { val i = listState.layoutInfo; (i.visibleItemsInfo.lastOrNull()?.index ?: -1) >= i.totalItemsCount - 2 } }
	LaunchedEffect(total) { if (total > 0 && atBottom) listState.animateScrollToItem(total - 1) }
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
			cloud.summaryError?.let {
				Text("⚠ $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
			}
			SelectionContainer(Modifier.weight(1f).padding(top = 8.dp)) {
				Text(
					cloud.keyPoints.ifBlank { "目前無重點" },
					style = MaterialTheme.typography.bodyLarge,
					color = if (cloud.keyPoints.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.verticalScroll(rememberScrollState()),
				)
			}
		}
	}
}
