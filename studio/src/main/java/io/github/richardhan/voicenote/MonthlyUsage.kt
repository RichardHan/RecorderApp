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
	fun record(body: JSONObject, header: String?, month: String = YearMonth.now().toString(), model: String = "", inputRate: String = "", outputRate: String = "") = synchronized(lock) {
		val usage = body.optJSONObject("usage") ?: body.optJSONObject("usageMetadata")
		fun count(vararg names: String): Long? = names.firstNotNullOfOrNull { usage?.optLong(it, -1)?.takeIf { n -> n >= 0 } }
		val tokens = count("total_tokens", "totalTokenCount")
		val input = count("prompt_tokens", "input_tokens", "promptTokenCount")
		val output = count("completion_tokens", "output_tokens", "candidatesTokenCount")
		val fee = reportedCostUsd(header) ?: reportedCostUsd(usage?.optString("cost"))
		val priceIn = input?.let { inputEstimateUsd(it, inputRate)?.toBigDecimalOrNull() }
		val priceOut = output?.let { inputEstimateUsd(it, outputRate)?.toBigDecimalOrNull() }
		val day = java.time.LocalDate.now().toString()
		val editor = prefs.edit()
		for (key in listOf(month, "day/$day")) {
			val row = row(key)
			fun add(field: String, amount: Long?) { if (amount != null) row.put(field, row.optLong(field) + amount) }
			fun decimal(field: String, amount: String?) { if (amount != null) row.put(field, (row.optString(field,"0").toBigDecimal() + amount.toBigDecimal()).toPlainString()) }
			add("requests", 1); add("tokens", tokens); add("input", input); add("output", output)
			if (tokens != null) add("withTokens", 1)
			if (fee != null) { decimal("cost", fee); add("withCost", 1) }
			if (priceIn != null && priceOut != null) { decimal("estimated", (priceIn + priceOut).toPlainString()); add("priced", 1) }
			if (model.isNotBlank()) {
				val models = row.optJSONObject("models") ?: JSONObject()
				models.put(model, models.optLong(model) + 1)
				row.put("models", models)
			}
			editor.putString(key, row.toString())
		}
		editor.apply()
	}
	fun months() = prefs.all.keys.filterNot { it.startsWith("day/") }.sortedDescending()
	fun row(month: String) = JSONObject(prefs.getString(month, "{}") ?: "{}")
	fun last30(): JSONObject {
		val totals = JSONObject()
		val today = java.time.LocalDate.now()
		for (n in 0L..29L) {
			val row = row("day/" + today.minusDays(n))
			for (k in listOf("requests","tokens","withTokens","withCost","input","output","priced")) if (row.has(k)) totals.put(k, totals.optLong(k) + row.getLong(k))
			for (k in listOf("estimated","cost")) if (row.has(k)) totals.put(k, (totals.optString(k,"0").toBigDecimal() + row.getString(k).toBigDecimal()).toPlainString())
		}
		return totals
	}
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
	val row = if (month == "30") usage.last30() else usage.row(month)
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.meeting_usage)) }, text = {
		Column {
			TextButton(onClick = { choose = true }, enabled = months.isNotEmpty()) { Text(if (month == "30") stringResource(R.string.ai_last30) else month) }
			DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
				DropdownMenuItem(text = { Text(stringResource(R.string.ai_last30)) }, onClick = { month = "30"; choose = false })
				for (item in months) DropdownMenuItem(text = { Text(item) }, onClick = { month = item; choose = false })
			}
			Text(stringResource(R.string.receipt_requests, row.optInt("requests")))
			if (row.has("tokens")) Text(stringResource(R.string.receipt_tokens, row.getLong("tokens")))
			if (row.has("input") || row.has("output")) Text(stringResource(R.string.ai_io_tokens, row.optString("input", "—"), row.optString("output", "—")))
			if (row.has("estimated")) Text(stringResource(R.string.ai_estimated_usage, row.getString("estimated"), row.getInt("priced")))
			row.optJSONObject("models")?.let { models -> for (id in models.keys()) Text("$id · " + models.getInt(id)) }
			if (row.has("cost")) Text(stringResource(R.string.meeting_known_cost, row.getString("cost")))
			Text(stringResource(R.string.meeting_usage_coverage, row.optInt("withTokens"), row.optInt("withCost")))
		}
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}
