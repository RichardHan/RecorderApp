package com.eva.feature_settings.composables.cloudai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.settings.ModelShortcuts

/** One-line tappable model IDs: recently used first, then presets. The selected value is highlighted. */
@Composable
internal fun ModelShortcutChips(
	current: String,
	recents: List<String>,
	presets: List<String>,
	onPick: (String) -> Unit,
	modifier: Modifier = Modifier,
	testTag: String = "model-shortcuts",
) {
	val items = remember(recents, presets) { ModelShortcuts.chips(recents, presets) }
	if (items.isEmpty()) return
	val selected = current.trim()
	Row(
		modifier = modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.testTag(testTag),
		horizontalArrangement = Arrangement.spacedBy(6.dp),
	) {
		items.forEach { id ->
			FilterChip(
				selected = id == selected,
				onClick = { onPick(id) },
				label = { Text(id, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
			)
		}
	}
}
