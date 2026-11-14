package com.eva.feature_player.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.model.PacedLine
import com.eva.cloudtranscribe.model.PlaybackTranscripts
import com.eva.cloudtranscribe.model.SpeechPace
import com.eva.cloudtranscribe.model.SpeechPaceReport
import com.eva.cloudtranscribe.model.TranscriptRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun paceClock(ms: Long): String {
	val t = (ms / 1000).coerceAtLeast(0)
	return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60) else "%02d:%02d".format(t / 60, t % 60)
}

/** Pure text for tests. */
internal fun paceSummary(r: SpeechPaceReport): String = buildString {
	append("共 ${r.units} 字")
	r.perMinute?.let { append("・平均每分鐘 ${it.toInt()} 字（含段內停頓）") }
	if (r.perMinute == null) append("・沒有時間戳，無法計算語速")
}

/** Local pace/filler card; uses the same default transcript as the player. Nothing leaves the phone. */
@Composable
internal fun SpeechPaceCard(transcripts: List<TranscriptRecord>, onSeekTo: (Long) -> Unit) {
	val record = PlaybackTranscripts.defaultSelection(transcripts) ?: return
	val report by produceState<SpeechPaceReport?>(null, record.id, record.updatedAtMillis) {
		value = withContext(Dispatchers.Default) { SpeechPace.report(PlaybackTranscripts.lines(record)) }
	}
	val r = report ?: return
	if (r.units == 0) return
	Card {
		Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text("說話節奏（本機計算）", style = MaterialTheme.typography.titleSmall)
			Text(paceSummary(r), style = MaterialTheme.typography.bodySmall)
			PaceRow("最快", r.fastest, onSeekTo)
			PaceRow("最慢", r.slowest, onSeekTo)
			if (r.fillers.isEmpty()) Text("沒有偵測到常見贅詞", style = MaterialTheme.typography.bodySmall)
			else Text(
				"常見贅詞：" + r.fillers.joinToString("、") { "${it.first} ${it.second}" } + "（每千字 ${"%.1f".format(r.fillersPerThousand)} 次）",
				style = MaterialTheme.typography.bodySmall,
			)
			Text(
				"統計整段錄音所有人（沒有說話者分離）；雲端轉錄常省略「嗯、呃」，贅詞可能偏低。只供參考，不是評分。",
				style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun PaceRow(label: String, line: PacedLine?, onSeekTo: (Long) -> Unit) {
	line ?: return
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text("$label：每分鐘 ${line.perMinute.toInt()} 字", style = MaterialTheme.typography.bodySmall)
		AssistChip(onClick = { onSeekTo(line.startMs) }, label = { Text(paceClock(line.startMs)) })
	}
}
