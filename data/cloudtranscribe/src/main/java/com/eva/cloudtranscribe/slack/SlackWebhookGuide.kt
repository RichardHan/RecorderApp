package com.eva.cloudtranscribe.slack

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Official Slack Incoming Webhooks guide opened from Settings › Slack 同步.
 * `https://api.slack.com/messaging/webhooks` redirects here; this trailing-slash URL is the canonical 200 page.
 */
object SlackWebhookGuide {
	const val URL = "https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/"

	fun viewIntent(url: String = URL): Intent =
		Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addCategory(Intent.CATEGORY_BROWSABLE) }

	/**
	 * Opens [URL] with [Intent.ACTION_VIEW]. Returns false when no browser (or other handler) is available;
	 * the caller should show a short toast. Never logs or includes a webhook URL.
	 */
	fun open(startActivity: (Intent) -> Unit): Boolean = try {
		startActivity(viewIntent())
		true
	} catch (_: ActivityNotFoundException) {
		false
	}

	fun open(context: Context): Boolean = open(context::startActivity)
}
