package com.eva.feature_player.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.citations.Citation
import com.eva.cloudtranscribe.citations.SummaryCitations

/** Pure grouping for tests: each summary line with its verified citations (lines without any have none). */
internal fun citedLines(text: String, citations: List<Citation>): List<Pair<String, List<Citation>>> {
	val byLine = citations.groupBy { it.lineIndex }
	return text.lines().mapIndexed { i, line -> line to byLine[i].orEmpty().sortedBy { it.startMs } }
}

/** Summary lines with small time chips; tapping a chip seeks to that cloud segment's real start time. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CitedSummaryText(text: String, citations: List<Citation>, onSeekTo: (Long) -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		citedLines(text, citations).forEach { (line, cites) ->
			if (cites.isEmpty()) {
				if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall)
			} else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
				Text(line, style = MaterialTheme.typography.bodySmall)
				cites.forEach { c ->
					val label = SummaryCitations.clock(c.startMs)
					AssistChip(
						onClick = { onSeekTo(c.startMs) },
						label = { Text(label, style = MaterialTheme.typography.labelSmall) },
						modifier = androidx.compose.ui.Modifier.semantics { contentDescription = "跳到 $label" },
					)
				}
			}
		}
	}
}
