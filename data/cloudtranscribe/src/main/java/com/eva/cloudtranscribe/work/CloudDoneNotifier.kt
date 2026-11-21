package com.eva.cloudtranscribe.work

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.eva.cloudtranscribe.model.RunStatus
import com.eva.utils.IntentConstants
import com.eva.utils.NavDeepLinks

/** Texts for "background cloud work finished" notifications (spec: market-done-notify). Pure for tests. */
object CloudDoneMessages {
	const val MAX_POINT = 60
	private val BULLET = Regex("""^\s*(?:[-*•·]|\d+[.)、])\s*""")

	private fun name(title: String?) = "「${title?.takeIf { it.isNotBlank() } ?: UiStrings.get(StringsRes.string.data_cloudtranscribe_recording_alt, "錄音")}」"

	/** Null when no notification should be shown (e.g. cancelled or still running). */
	fun transcription(status: RunStatus, title: String?): String? = when (status) {
		RunStatus.SUCCESS -> UiStrings.get(StringsRes.string.data_cloudtranscribe_cloud_transcription_complete, "%1\$s雲端轉錄完成", name(title))
		RunStatus.PARTIAL -> UiStrings.get(StringsRes.string.data_cloudtranscribe_cloud_transcription_partially_complete, "%1\$s雲端轉錄部分完成（有段落失敗）", name(title))
		RunStatus.FAILED -> UiStrings.get(StringsRes.string.data_cloudtranscribe_cloud_transcription_failed_alt, "%1\$s雲端轉錄失敗", name(title))
		else -> null
	}

	fun summary(status: RunStatus, title: String?, text: String, autoTitled: Boolean = false): String? = when (status) {
		RunStatus.SUCCESS -> {
			val first = text.lines().map { BULLET.replace(it.trim(), "") }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
			val point = first?.let { if (it.length > MAX_POINT) it.take(MAX_POINT) + "…" else it }
			UiStrings.get(StringsRes.string.data_cloudtranscribe_summary_complete, "%1\$s摘要完成", name(title)) + (if (autoTitled) UiStrings.get(StringsRes.string.auto_title_applied, "（已自動命名）") else "") + (point?.let { "：$it" } ?: "")
		}
		RunStatus.FAILED -> UiStrings.get(StringsRes.string.data_cloudtranscribe_summary_failed, "%1\$s摘要失敗", name(title))
		else -> null
	}
}

/** Posts a local notification; silently does nothing without permission. Lock screen shows no content. */
internal object CloudDoneNotifier {
	private const val CHANNEL = "cloud_ai_done"

	fun post(context: Context, recordingId: Long, kind: String, text: String) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
		) return
		val manager = NotificationManagerCompat.from(context)
		if (!manager.areNotificationsEnabled()) return
		manager.createNotificationChannel(
			NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(UiStrings.get(StringsRes.string.data_cloudtranscribe_cloud_job_complete, "雲端工作完成")).build(),
		)
		val intent = Intent(Intent.ACTION_VIEW, NavDeepLinks.audioPlayerDestinationUri(recordingId).toUri()).apply {
			setClassName(context.applicationContext, IntentConstants.MAIN_ACTIVITY)
			flags = Intent.FLAG_ACTIVITY_NEW_TASK
		}
		val id = "$kind-$recordingId".hashCode()
		val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
		val public = NotificationCompat.Builder(context, CHANNEL)
			.setSmallIcon(android.R.drawable.stat_sys_upload_done)
			.setContentTitle(UiStrings.get(StringsRes.string.data_cloudtranscribe_recorderapp_cloud_job_complete, "RecorderApp：雲端工作完成"))
			.build()
		val notification = NotificationCompat.Builder(context, CHANNEL)
			.setSmallIcon(android.R.drawable.stat_sys_upload_done)
			.setContentTitle(UiStrings.get(StringsRes.string.data_cloudtranscribe_recorderapp, "RecorderApp"))
			.setContentText(text)
			.setStyle(NotificationCompat.BigTextStyle().bigText(text))
			.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
			.setPublicVersion(public)
			.setContentIntent(pending)
			.setAutoCancel(true)
			.build()
		runCatching { manager.notify(id, notification) }
	}
}
