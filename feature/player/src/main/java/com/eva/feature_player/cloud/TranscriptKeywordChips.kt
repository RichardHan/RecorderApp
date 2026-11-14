package com.eva.feature_player.cloud

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Pure toggle for tests: tapping the active keyword clears the search, any other one replaces it. */
internal fun keywordQuery(current: String, tapped: String): String = if (current.trim().equals(tapped, ignoreCase = true)) "" else tapped

/** Locally computed frequent terms; tapping one fills the existing in-transcript search. */
@Composable
internal fun TranscriptKeywordChips(keywords: List<String>, query: String, onPick: (String) -> Unit) {
	if (keywords.isEmpty()) return
	Row(
		Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("transcript-keywords"),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		keywords.forEach { k ->
			FilterChip(
				selected = query.trim().equals(k, ignoreCase = true),
				onClick = { onPick(keywordQuery(query, k)) },
				label = { Text(k) },
			)
		}
	}
}
