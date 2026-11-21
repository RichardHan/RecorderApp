package com.eva.feature_settings.composables.cloudai

import com.eva.feature_settings.R as StringsRes
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.CloudUsage
import com.eva.cloudtranscribe.model.MonthUsage
import com.eva.cloudtranscribe.model.SummaryRecord
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.store.TranscriptStore
import com.eva.cloudtranscribe.usage.BatteryUsageSummary
import com.eva.cloudtranscribe.usage.CostEstimate
import com.eva.cloudtranscribe.usage.DeviceContextBatteryStore
import com.eva.cloudtranscribe.usage.DiskUsage
import com.eva.cloudtranscribe.usage.ModelPrice
import com.eva.cloudtranscribe.usage.PeriodUsage
import com.eva.cloudtranscribe.usage.TokenPricing
import com.eva.cloudtranscribe.usage.TokenUsageEvent
import com.eva.cloudtranscribe.usage.TokenUsageStore
import com.eva.recordings.domain.provider.StorageInfoProvider
import com.eva.recordings.domain.provider.VoiceRecordingsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class UsageSnapshot(
	val transcripts: List<TranscriptRecord>,
	val summaries: List<SummaryRecord>,
	val titleRequestAtMillis: List<Long>,
	val tokenEvents: List<TokenUsageEvent>,
	val diskAppBytes: Long,
	val phoneFreeBytes: Long?,
	val phoneTotalBytes: Long?,
	val battery: BatteryUsageSummary,
	val zone: ZoneId,
	val nowMillis: Long,
)

@HiltViewModel
internal class CloudUsageViewModel @Inject constructor(
	private val store: TranscriptStore,
	private val tokenStore: TokenUsageStore,
	private val batteryStore: DeviceContextBatteryStore,
	private val recordings: VoiceRecordingsProvider,
	private val storage: StorageInfoProvider,
	@ApplicationContext private val context: Context,
) : ViewModel() {
	var snapshot by mutableStateOf<UsageSnapshot?>(null)
		private set
	var loading by mutableStateOf(false)
		private set

	fun load() {
		if (loading) return
		loading = true
		viewModelScope.launch {
			try {
				snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
			} finally {
				loading = false
			}
		}
	}

	private suspend fun loadSnapshot(): UsageSnapshot {
		val ids = store.recordingIds()
		val transcripts = ids.flatMap { runCatching { store.transcripts(it) }.getOrDefault(emptyList()) }
		val summaries = ids.flatMap { runCatching { store.summaries(it) }.getOrDefault(emptyList()) }
		val titleRequests = ids.flatMap { runCatching { store.autoTitleMarker(it)?.requestAtMillis.orEmpty() }.getOrDefault(emptyList()) }
		val events = runCatching { tokenStore.events() }.getOrDefault(emptyList())
		val recordingBytes = runCatching { recordings.getVoiceRecordings().sumOf { it.sizeInBytes.coerceAtLeast(0L) } }.getOrDefault(0L)
		val sidecarBytes = DiskUsage.totalBytes(
			listOf(
				DiskUsage.directoryBytes(File(context.filesDir, "ai_transcripts")),
				DiskUsage.directoryBytes(File(context.filesDir, "follow_up_drafts")),
				DiskUsage.directoryBytes(File(context.filesDir, "clips")),
				DiskUsage.directoryBytes(File(context.filesDir, "trash_recordings")),
				DiskUsage.directoryBytes(File(context.noBackupFilesDir, "recording_photos")),
				DiskUsage.directoryBytes(File(context.cacheDir, "temp_recordings")),
			),
		)
		val phone = storage.invoke().getOrNull()
		return UsageSnapshot(
			transcripts = transcripts,
			summaries = summaries,
			titleRequestAtMillis = titleRequests,
			tokenEvents = events,
			diskAppBytes = recordingBytes + sidecarBytes,
			phoneFreeBytes = phone?.freeAmountInBytes,
			phoneTotalBytes = phone?.totalAmountInBytes,
			battery = runCatching { batteryStore.summarize() }.getOrDefault(BatteryUsageSummary()),
			zone = ZoneId.systemDefault(),
			nowMillis = System.currentTimeMillis(),
		)
	}
}

internal fun monthsOf(snapshot: UsageSnapshot, prices: List<ModelPrice>): List<MonthUsage> =
	CloudUsage.summarize(
		transcripts = snapshot.transcripts,
		summaries = snapshot.summaries,
		zone = snapshot.zone,
		nowMillis = snapshot.nowMillis,
		titleRequestAtMillis = snapshot.titleRequestAtMillis,
		tokenEvents = snapshot.tokenEvents,
		prices = prices,
	)

internal fun last30Of(snapshot: UsageSnapshot, prices: List<ModelPrice>): PeriodUsage =
	CloudUsage.lastDays(snapshot.tokenEvents, prices, snapshot.nowMillis)

/** Argument order of `cloud_usage_month`; kept so older tests and the fallback line stay aligned. */
internal fun usageArgs(u: MonthUsage): List<Any> =
	listOf(u.month.toString(), u.cloudTranscripts, CloudUsage.minutes(u.uploadedMs), u.requests, u.summaries)

/** Shown inside the collapsed-by-default 用量 card; computes only when the card is opened. Reads local records only. */
@Composable
internal fun CloudUsageSection(
	prices: List<ModelPrice> = emptyList(),
	transcribeModel: String = "",
	summaryModel: String = "",
	onPricesChange: (List<ModelPrice>) -> Unit = {},
	viewModel: CloudUsageViewModel = hiltViewModel(),
) {
	androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		val snapshot = viewModel.snapshot
		when {
			snapshot == null -> CircularProgressIndicator()
			else -> {
				val months = monthsOf(snapshot, prices)
				val visible = CloudUsage.visibleMonths(months)
				val current = months.firstOrNull()
				val history = visible.drop(1)
				if (current != null && !current.isEmpty) {
					UsageBlockTitle(stringResource(StringsRes.string.cloud_usage_this_month, current.month.toString()))
					TokenAndCostBlock(current.promptTokens, current.completionTokens, current.tokenRequests, current.cost)
					FallbackCounts(current)
				} else {
					Text(stringResource(StringsRes.string.cloud_usage_empty_cloud), style = MaterialTheme.typography.bodySmall)
				}
				val last30 = last30Of(snapshot, prices)
				if (last30.tokenRequests > 0) {
					UsageBlockTitle(stringResource(StringsRes.string.cloud_usage_last_30_days))
					TokenAndCostBlock(last30.promptTokens, last30.completionTokens, last30.tokenRequests, last30.cost)
				}
				if (history.isNotEmpty()) {
					var open by rememberSaveable { mutableStateOf(false) }
					TextButton(onClick = { open = !open }, modifier = Modifier.testTag("usage-history-toggle")) {
						Text(if (open) stringResource(StringsRes.string.cloud_usage_history) + " ▾" else stringResource(StringsRes.string.cloud_usage_history) + " ▸")
					}
					if (open) {
						Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							history.forEach { month ->
								UsageBlockTitle(month.month.toString())
								TokenAndCostBlock(month.promptTokens, month.completionTokens, month.tokenRequests, month.cost)
								FallbackCounts(month)
							}
						}
					}
				}
				UsageBlockTitle(stringResource(StringsRes.string.cloud_usage_disk))
				Text(
					stringResource(StringsRes.string.cloud_usage_disk_app, DiskUsage.formatBytes(snapshot.diskAppBytes)),
					style = MaterialTheme.typography.bodySmall,
				)
				val free = snapshot.phoneFreeBytes
				val total = snapshot.phoneTotalBytes
				if (free != null && total != null && total > 0L) {
					Text(
						stringResource(StringsRes.string.cloud_usage_disk_phone, DiskUsage.formatBytes(free), DiskUsage.formatBytes(total)),
						style = MaterialTheme.typography.bodySmall,
					)
				}
				UsageBlockTitle(stringResource(StringsRes.string.cloud_usage_battery))
				val battery = snapshot.battery
				if (battery.hasData) {
					Text(
						stringResource(StringsRes.string.cloud_usage_battery_used, battery.drainPercent, battery.recordingsWithData),
						style = MaterialTheme.typography.bodySmall,
					)
				} else {
					Text(stringResource(StringsRes.string.cloud_usage_battery_none), style = MaterialTheme.typography.bodySmall)
				}
				if (battery.chargingSessions > 0) {
					Text(
						stringResource(StringsRes.string.cloud_usage_battery_charging_note, battery.chargingSessions),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
		PriceEditor(
			prices = prices,
			transcribeModel = transcribeModel,
			summaryModel = summaryModel,
			onPricesChange = onPricesChange,
		)
		Text(stringResource(StringsRes.string.cloud_usage_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Text(stringResource(StringsRes.string.cloud_usage_scope), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun UsageBlockTitle(text: String) {
	Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun TokenAndCostBlock(prompt: Long, completion: Long, tokenRequests: Int, cost: CostEstimate) {
	if (tokenRequests == 0 && prompt == 0L && completion == 0L) {
		Text(stringResource(StringsRes.string.cloud_usage_no_tokens_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		return
	}
	Text(
		stringResource(StringsRes.string.cloud_usage_tokens, TokenPricing.formatTokens(prompt), TokenPricing.formatTokens(completion)),
		style = MaterialTheme.typography.bodySmall,
	)
	when {
		cost.canShowAmount && cost.hasUnsetPrice -> Text(
			stringResource(StringsRes.string.cloud_usage_cost_partial, TokenPricing.formatCost(cost.amount!!)),
			style = MaterialTheme.typography.bodySmall,
		)
		cost.canShowAmount -> Text(
			stringResource(StringsRes.string.cloud_usage_cost, TokenPricing.formatCost(cost.amount!!)),
			style = MaterialTheme.typography.bodySmall,
		)
		else -> Text(stringResource(StringsRes.string.cloud_usage_cost_unset), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun FallbackCounts(month: MonthUsage) {
	if (month.cloudTranscripts == 0 && month.requests == 0 && month.summaries == 0) return
	Text(
		stringResource(
			StringsRes.string.cloud_usage_fallback_counts,
			month.cloudTranscripts,
			CloudUsage.minutes(month.uploadedMs),
			month.requests,
			month.summaries,
		),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun PriceEditor(
	prices: List<ModelPrice>,
	transcribeModel: String,
	summaryModel: String,
	onPricesChange: (List<ModelPrice>) -> Unit,
) {
	val models = TokenPricing.normalizePrices(prices, listOf(transcribeModel, summaryModel).filter { it.isNotBlank() })
	if (models.isEmpty()) return
	Text(stringResource(StringsRes.string.cloud_usage_prices_title), style = MaterialTheme.typography.bodyLarge)
	models.forEach { price ->
		Text(price.model, style = MaterialTheme.typography.bodySmall)
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedTextField(
				value = price.inputPerMillion,
				onValueChange = { value -> onPricesChange(upsertPrice(models, price.model, input = value, output = price.outputPerMillion)) },
				modifier = Modifier.weight(1f).testTag("usage-price-input-${price.model}"),
				label = { Text(stringResource(StringsRes.string.cloud_usage_price_input)) },
				singleLine = true,
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
			)
			OutlinedTextField(
				value = price.outputPerMillion,
				onValueChange = { value -> onPricesChange(upsertPrice(models, price.model, input = price.inputPerMillion, output = value)) },
				modifier = Modifier.weight(1f).testTag("usage-price-output-${price.model}"),
				label = { Text(stringResource(StringsRes.string.cloud_usage_price_output)) },
				singleLine = true,
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
			)
		}
	}
}

internal fun upsertPrice(prices: List<ModelPrice>, model: String, input: String, output: String): List<ModelPrice> {
	val name = model.trim()
	if (name.isEmpty()) return prices
	val index = prices.indexOfFirst { it.model.equals(name, ignoreCase = true) }
	return if (index >= 0) {
		prices.toMutableList().also { it[index] = ModelPrice(prices[index].model, input, output) }
	} else prices + ModelPrice(name, input, output)
}
