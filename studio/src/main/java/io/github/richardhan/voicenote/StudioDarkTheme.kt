package io.github.richardhan.voicenote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.sqrt

internal val studioDarkColors = darkColorScheme(
	primary = Color(0xFFFFA191),
	onPrimary = Color(0xFF401C16),
	secondary = Color(0xFFDEC1BC),
	primaryContainer = Color(0xFF66392F),
	onPrimaryContainer = Color(0xFFFFDAD2),
	background = Color(0xFF131313),
	surface = Color(0xFF131313),
	onSurface = Color(0xFFF6F2EF),
	onSurfaceVariant = Color(0xFFCFC5C2),
	surfaceContainer = Color(0xFF242323),
	surfaceContainerHigh = Color(0xFF302E2D),
	surfaceContainerHighest = Color(0xFF3B3938),
	outline = Color(0xFF9F918F),
	outlineVariant = Color(0xFF514A48)
)

@Composable
internal fun LiveWaveform(library: RecordingLibrary, accent: Color) {
	val started = library.started
	val capacity = 56
	val samples = remember(started) { mutableStateListOf<Float>() }
	LaunchedEffect(started) {
		if (started == 0L) return@LaunchedEffect
		while (true) {
			val peak = library.peakAmplitude().coerceIn(0, 32767)
			if (samples.size == capacity) samples.removeAt(0)
			samples.add(sqrt(peak / 32767f))
			delay(100)
		}
	}
	Canvas(Modifier.fillMaxWidth().height(64.dp)) {
		val mid = size.height / 2
		val step = size.width / capacity
		for (i in 0 until capacity) {
			val x = (i + 0.5f) * step
			val sample = samples.getOrNull(i + samples.size - capacity)
			val half = (mid * (sample ?: 0f) * 0.92f).coerceAtLeast(1.dp.toPx())
			drawLine(
				color = if (sample != null) accent else Color.White.copy(alpha = 0.32f),
				start = Offset(x, mid - half), end = Offset(x, mid + half),
				strokeWidth = step * 0.48f, cap = StrokeCap.Round
			)
		}
	}
}
