package com.eva.feature_player.cloud

import com.eva.feature_player.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

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
	append(UiStrings.get(StringsRes.string.feature_player_characters, "共 %1\$s 字", r.units))
	r.perMinute?.let { append(UiStrings.get(StringsRes.string.feature_player_average_characters_min_including_pauses, "・平均每分鐘 %1\$s 字（含段內停頓）", it.toInt())) }
	if (r.perMinute == null) append(UiStrings.get(StringsRes.string.feature_player_no_timestamps_available_for_pace_calculation, "・沒有時間戳，無法計算語速"))
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
			Text(stringResource(StringsRes.string.feature_player_speaking_pace_on_device), style = MaterialTheme.typography.titleSmall)
			Text(paceSummary(r), style = MaterialTheme.typography.bodySmall)
			PaceRow(UiStrings.get(StringsRes.string.feature_player_fastest, "最快"), r.fastest, onSeekTo)
			PaceRow(UiStrings.get(StringsRes.string.feature_player_slowest, "最慢"), r.slowest, onSeekTo)
			if (r.fillers.isEmpty()) Text(stringResource(StringsRes.string.feature_player_no_common_fillers_detected), style = MaterialTheme.typography.bodySmall)
			else Text(
				stringResource(StringsRes.string.feature_player_common_fillers) + r.fillers.joinToString("、") { "${it.first} ${it.second}" } + UiStrings.get(StringsRes.string.feature_player_per_thousand_characters, "（每千字 %1\$s 次）", "%.1f".format(r.fillersPerThousand)),
				style = MaterialTheme.typography.bodySmall,
			)
			Text(
				stringResource(StringsRes.string.feature_player_includes_all_speakers_cloud_transcripts_may_omit),
				style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun PaceRow(label: String, line: PacedLine?, onSeekTo: (Long) -> Unit) {
	line ?: return
	Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(stringResource(StringsRes.string.feature_player_characters_min, (label).toString(), (line.perMinute.toInt()).toString()), style = MaterialTheme.typography.bodySmall)
		AssistChip(onClick = { onSeekTo(line.startMs) }, label = { Text(paceClock(line.startMs)) })
	}
}
