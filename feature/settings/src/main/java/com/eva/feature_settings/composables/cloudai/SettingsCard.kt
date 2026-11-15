package com.eva.feature_settings.composables.cloudai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Collapsible settings group: title + one-line status in the header; expanded state survives rotation. */
@Composable
internal fun SettingsCard(
	title: String,
	status: String?,
	key: String,
	initiallyExpanded: Boolean = true,
	content: @Composable () -> Unit,
) {
	var open by rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
	ElevatedCard(Modifier.fillMaxWidth()) {
		Row(
			Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.titleMedium)
				if (!status.isNullOrBlank()) Text(
					status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
				)
			}
			Text(if (open) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
		}
		AnimatedVisibility(open) {
			Column(
				Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) { content() }
		}
	}
}

/** Short label (bodyLarge) + optional one-line secondary text (bodySmall) + switch. */
@Composable
internal fun SettingSwitch(label: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(Modifier.weight(1f).padding(end = 8.dp)) {
			Text(label, style = MaterialTheme.typography.bodyLarge)
			if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Switch(checked, onChange)
	}
}

/** One-line hint with a 「詳細」 toggle that reveals the longer explanation. */
@Composable
internal fun InfoLine(short: String, detail: String? = null) {
	var open by rememberSaveable(short) { mutableStateOf(false) }
	Column {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(short, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			if (detail != null) TextButton({ open = !open }) { Text(if (open) "收合" else "ⓘ 詳細") }
		}
		if (open && detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

internal fun onOff(on: Boolean) = if (on) "已開啟" else "關閉"
