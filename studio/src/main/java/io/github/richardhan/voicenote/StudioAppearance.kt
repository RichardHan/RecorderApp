package io.github.richardhan.voicenote

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class Appearance(@param:StringRes val label: Int) {
	System(R.string.theme_system), Light(R.string.theme_light), Dark(R.string.theme_dark)
}

@Composable
internal fun ComponentActivity.StudioTheme(content: @Composable (Appearance, Boolean, (Appearance) -> Unit) -> Unit) {
	val preferences = remember { getSharedPreferences("appearance", Context.MODE_PRIVATE) }
	var appearance by remember {
		val stored = preferences.getString("mode", null)
		mutableStateOf(Appearance.entries.firstOrNull { it.name == stored } ?: Appearance.System)
	}
	val dark = when (appearance) {
		Appearance.System -> isSystemInDarkTheme()
		Appearance.Dark -> true
		Appearance.Light -> false
	}
	LaunchedEffect(dark) {
		val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
		enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
	}
	MaterialTheme(colorScheme = if (dark) studioDarkColors else studioLightColors) {
		content(appearance, dark) { choice ->
			appearance = choice
			preferences.edit().putString("mode", choice.name).apply()
		}
	}
}

private val studioLightColors = lightColorScheme(primary = Color(0xFF58308C), surface = Color(0xFFFAF8FC))

@Composable
internal fun StudioHeading(appearance: Appearance, choose: (Appearance) -> Unit) {
	var open by remember { mutableStateOf(false) }
	val title = stringResource(R.string.appearance)
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(stringResource(R.string.studio), Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
		val ink = MaterialTheme.colorScheme.onSurface
		IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = title }) {
			Canvas(Modifier.size(24.dp)) {
				val radius = size.minDimension * 0.4f
				drawCircle(ink, radius, style = Stroke(2.dp.toPx()))
				drawArc(ink, 90f, 180f, useCenter = true,
					topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2, radius * 2))
			}
		}
	}
	if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
		text = {
			Column(Modifier.selectableGroup()) {
				Appearance.entries.forEach { mode ->
					Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
						selected = appearance == mode, role = Role.RadioButton,
						onClick = { choose(mode); open = false }
					), verticalAlignment = Alignment.CenterVertically) {
						RadioButton(selected = appearance == mode, onClick = null)
						Text(stringResource(mode.label), Modifier.padding(start = 12.dp))
					}
				}
			}
		},
		confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } }
	)
}
