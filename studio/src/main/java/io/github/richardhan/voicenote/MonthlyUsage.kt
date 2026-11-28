package io.github.richardhan.voicenote

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.json.JSONObject
import java.time.YearMonth

/** Only aggregate response metadata. No transcript, credentials or request bodies enter this store. */
internal class MonthlyUsage(context: Context) {
	private val prefs = context.getSharedPreferences("studio_ai_usage", Context.MODE_PRIVATE)
	fun record(body: JSONObject, header: String?, month: String = YearMonth.now().toString()) = synchronized(lock) {
		val usage = body.optJSONObject("usage") ?: body.optJSONObject("usageMetadata")
		val tokens = usage?.optLong("total_tokens", usage.optLong("totalTokenCount", -1))?.takeIf { it >= 0 }
		val cost = reportedCostUsd(header) ?: reportedCostUsd(usage?.optString("cost"))
		val row = JSONObject(prefs.getString(month, "{}") ?: "{}")
		row.put("requests", row.optLong("requests") + 1)
		if (tokens != null) row.put("tokens", row.optLong("tokens") + tokens).put("withTokens", row.optLong("withTokens") + 1)
		if (cost != null) row.put("cost", (row.optString("cost", "0").toBigDecimal() + cost.toBigDecimal()).toPlainString())
			.put("withCost", row.optLong("withCost") + 1)
		prefs.edit().putString(month, row.toString()).apply()
	}
	fun months() = prefs.all.keys.sortedDescending()
	fun row(month: String) = JSONObject(prefs.getString(month, "{}") ?: "{}")
	private companion object { val lock = Any() }
}

@Composable
internal fun UsagePanel() {
	val context = LocalContext.current
	val usage = remember(context) { MonthlyUsage(context) }
	var open by remember { mutableStateOf(false) }
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.meeting_usage)) }
	if (!open) return
	val months = usage.months()
	var month by remember { mutableStateOf(months.firstOrNull() ?: YearMonth.now().toString()) }
	var choose by remember { mutableStateOf(false) }
	val row = usage.row(month)
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.meeting_usage)) }, text = {
		Column {
			TextButton(onClick = { choose = true }, enabled = months.isNotEmpty()) { Text(month) }
			DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
				for (item in months) DropdownMenuItem(text = { Text(item) }, onClick = { month = item; choose = false })
			}
			Text(stringResource(R.string.receipt_requests, row.optInt("requests")))
			if (row.has("tokens")) Text(stringResource(R.string.receipt_tokens, row.getLong("tokens")))
			if (row.has("cost")) Text(stringResource(R.string.meeting_known_cost, row.getString("cost")))
			Text(stringResource(R.string.meeting_usage_coverage, row.optInt("withTokens"), row.optInt("withCost")))
		}
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}
