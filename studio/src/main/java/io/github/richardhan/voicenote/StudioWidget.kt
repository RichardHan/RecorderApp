package io.github.richardhan.voicenote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

internal const val OPEN_LIBRARY = "io.github.richardhan.voicenote.LIBRARY"
internal const val OPEN_RECORDER = "io.github.richardhan.voicenote.RECORDER"

class StudioWidget : AppWidgetProvider() {
	override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
		val library = (context.applicationContext as StudioApplication).library
		val recent = library.files.firstOrNull()
		val views = RemoteViews(context.packageName, R.layout.studio_widget)
		fun open(action: String) = PendingIntent.getActivity(context, 0, Intent(context, StudioActivity::class.java).setAction(action),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		views.setOnClickPendingIntent(R.id.open_recorder, open(OPEN_RECORDER))
		views.setOnClickPendingIntent(R.id.open_library, open(OPEN_LIBRARY))
		views.setTextViewText(R.id.open_library, recent?.let { library.catalog.title(it) } ?: context.getString(R.string.library))
		for (id in ids) manager.updateAppWidget(id, views)
	}
}

internal fun Context.refreshWidgets() {
	val manager = AppWidgetManager.getInstance(this)
	val ids = manager.getAppWidgetIds(ComponentName(this, StudioWidget::class.java))
	if (ids.isNotEmpty()) StudioWidget().onUpdate(this, manager, ids)
}
