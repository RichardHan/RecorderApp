package com.eva.feature_widget.recorder

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.state.GlanceStateDefinition
import com.eva.feature_widget.R
import com.eva.feature_widget.proto.RecorderWidgetDataProto
import com.eva.feature_widget.recorder.composable.RecorderWidgetContent
import com.eva.feature_widget.recorder.models.toModel
import com.eva.feature_widget.recorder.repository.RecorderWidgetRepoImpl.Companion.FILE_LOCATION
import com.eva.feature_widget.recorder.repository.RecorderWidgetRepoImpl.Companion.recorderWidgetData
import com.eva.feature_widget.utils.RecorderAppWidgetTheme
import com.eva.recorder.domain.widget.RecorderWidgetPolicy
import com.eva.utils.IntentConstants
import com.eva.utils.NavDeepLinks
import java.io.File


class AppRecorderWidget : GlanceAppWidget() {

	override val sizeMode: SizeMode
		get() = SizeMode.Exact

	override val stateDefinition: GlanceStateDefinition<RecorderWidgetDataProto>
		get() = object : GlanceStateDefinition<RecorderWidgetDataProto> {

			override suspend fun getDataStore(context: Context, fileKey: String)
					: DataStore<RecorderWidgetDataProto> = context.recorderWidgetData

			override fun getLocation(context: Context, fileKey: String)
					: File = context.dataStoreFile(FILE_LOCATION)

		}


	override suspend fun provideGlance(context: Context, id: GlanceId) {
		provideContent {

			val protoState = currentState<RecorderWidgetDataProto>()
			val model = protoState.toModel()
			val tryStart = RecorderWidgetPolicy.shouldStartOnTap(model.state)
			val destination = NavDeepLinks.RECORDER_DESTINATION_PATTERN.toUri()
				.buildUpon()
				.appendQueryParameter("src", if (tryStart) "widget-start" else "widget-open")
				.build()

			RecorderAppWidgetTheme {
				RecorderWidgetContent(
					model = model,
					modifier = GlanceModifier
						.clickable(
							onClick = actionStartActivity(
								Intent(Intent.ACTION_VIEW, destination).apply {
									setClassName(
										context.applicationContext,
										IntentConstants.MAIN_ACTIVITY
									)
									flags = Intent.FLAG_ACTIVITY_NEW_TASK
									if (tryStart) putExtra(IntentConstants.EXTRAS_WIDGET_TRY_START, true)
								}
							),
						)
				)
			}
		}
	}

	override fun onCompositionError(
		context: Context,
		glanceId: GlanceId,
		appWidgetId: Int,
		throwable: Throwable,
	) {
		// print stacktrace
		throwable.printStackTrace()
		// update the layout
		val widgetManager = context.getSystemService<AppWidgetManager>() ?: return

		val remoteView = RemoteViews(context.packageName, R.layout.widget_loading_failed_layout)
			.apply {
				val message = throwable.message ?: context.getString(R.string.widget_error_text)
				setTextViewText(R.id.widget_error_description, message)
			}

		// show the error on the widget
		widgetManager.updateAppWidget(appWidgetId, remoteView)
	}

}