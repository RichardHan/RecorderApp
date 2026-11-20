package com.eva.feature_recorder.composable

import com.eva.feature_recorder.R as StringsRes
import com.eva.utils.UiStrings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eva.recorder.utils.DeferredRecordedPointList

/** Thin live mic-level bar; reads the latest non-padding RMS (already 0–1) from the recorder points. */
@Composable
internal fun RecorderInputLevelBar(
	points: DeferredRecordedPointList,
	modifier: Modifier = Modifier,
	height: Dp = 6.dp,
) {
	val fill = MaterialTheme.colorScheme.secondary
	val track = MaterialTheme.colorScheme.surfaceContainerHigh
	androidx.compose.foundation.layout.Spacer(
		modifier
			.fillMaxWidth()
			.height(height)
			.clip(RoundedCornerShape(50))
			.semantics { contentDescription = UiStrings.get(StringsRes.string.feature_recorder_input_level, "輸入音量") }
			.drawWithCache {
				onDrawBehind {
					drawRect(track)
					val level = points().lastOrNull { !it.isPaddingPoint }?.rmsValue?.coerceIn(0f, 1f) ?: 0f
					if (level > 0f) drawRect(fill, size = Size(size.width * level, size.height))
				}
			},
	)
}
