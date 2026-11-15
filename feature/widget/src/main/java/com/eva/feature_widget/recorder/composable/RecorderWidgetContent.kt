package com.eva.feature_widget.recorder.composable

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentSize
import androidx.glance.unit.ColorProvider
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.eva.feature_widget.R
import com.eva.feature_widget.recorder.models.RecorderModel
import com.eva.feature_widget.utils.RecorderAppWidgetTheme
import com.eva.recorder.domain.models.RecorderState
import com.eva.utils.LocalTimeFormats
import kotlinx.datetime.LocalTime
import kotlinx.datetime.format

private val RecordingRed = ColorProvider(Color(0xFFB40000))

@Composable
@GlanceComposable
internal fun RecorderWidgetContent(
	model: RecorderModel,
	modifier: GlanceModifier = GlanceModifier,
) {
	val context = LocalContext.current
	val compact = LocalSize.current.width < 100.dp
	val recording = model.state == RecorderState.RECORDING || model.state == RecorderState.PAUSED
	val status = when (model.state) {
		RecorderState.RECORDING -> context.getString(R.string.recorder_state_recording)
		RecorderState.PAUSED -> context.getString(R.string.recorder_state_paused)
		else -> context.getString(R.string.widget_recorder_widget)
	}

	val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		GlanceTheme.colors.widgetBackground
	else GlanceTheme.colors.background

	Scaffold(
		backgroundColor = background,
		modifier = modifier,
		horizontalPadding = if (compact) 4.dp else 12.dp,
	) {
		if (compact) {
			Column(
				modifier = GlanceModifier.fillMaxSize(),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalAlignment = Alignment.CenterVertically,
			) {
				StatusBadge(recording)
				Spacer(modifier = GlanceModifier.height(4.dp))
				Text(
					text = if (recording) status else context.getString(R.string.widget_recorder_widget),
					style = TextStyle(
						color = if (recording) RecordingRed else GlanceTheme.colors.onBackground,
						fontWeight = FontWeight.Medium,
						fontSize = 10.sp,
					),
					maxLines = 1,
				)
			}
		} else {
			Row(
				modifier = GlanceModifier.fillMaxSize().padding(vertical = 8.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalAlignment = Alignment.CenterVertically,
			) {
				StatusBadge(recording)
				Spacer(modifier = GlanceModifier.width(12.dp))
				Column(modifier = GlanceModifier.wrapContentSize(), horizontalAlignment = Alignment.Start) {
					if (recording) {
						Text(
							text = model.time.format(LocalTimeFormats.LOCALTIME_FORMAT_MM_SS),
							style = TextStyle(
								color = GlanceTheme.colors.onBackground,
								fontWeight = FontWeight.Medium,
								fontSize = 18.sp,
							),
						)
						Spacer(modifier = GlanceModifier.height(2.dp))
						Text(
							text = status,
							style = TextStyle(color = RecordingRed, fontWeight = FontWeight.Medium, fontSize = 12.sp),
							maxLines = 1,
						)
					} else {
						Text(
							text = context.getString(R.string.widget_recorder_widget),
							style = TextStyle(
								color = GlanceTheme.colors.onBackground,
								fontWeight = FontWeight.Medium,
								fontSize = 16.sp,
							),
							maxLines = 1,
						)
						Spacer(modifier = GlanceModifier.height(2.dp))
						Text(
							text = context.getString(R.string.widget_recorder_tap_hint),
							style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
							maxLines = 1,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun StatusBadge(recording: Boolean) {
	Box(
		modifier = GlanceModifier
			.size(40.dp)
			.cornerRadius(20.dp)
			.background(if (recording) RecordingRed else GlanceTheme.colors.primaryContainer),
		contentAlignment = Alignment.Center,
	) {
		if (recording) {
			Box(
				modifier = GlanceModifier.size(12.dp).cornerRadius(6.dp).background(ColorProvider(Color.White)),
			) {}
		} else {
			Image(
				provider = ImageProvider(R.drawable.ic_widget_mic),
				contentDescription = null,
				modifier = GlanceModifier.size(24.dp),
				colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
			)
		}
	}
}

@GlanceRecorderWidgetPreview
@Composable
private fun RecorderWidgetPreview() = RecorderAppWidgetTheme {
	RecorderWidgetContent(model = RecorderModel(state = RecorderState.IDLE, time = LocalTime.fromSecondOfDay(0)))
}

@GlanceRecorderWidgetPreview
@Composable
private fun RecorderWidgetRecordingPreview() = RecorderAppWidgetTheme {
	RecorderWidgetContent(model = RecorderModel(state = RecorderState.RECORDING, time = LocalTime.fromSecondOfDay(5)))
}
