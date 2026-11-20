package com.eva.feature_settings.composables.cloudai

import com.eva.feature_settings.R as StringsRes
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.cloudtranscribe.model.CloudUsage
import com.eva.cloudtranscribe.model.MonthUsage
import com.eva.cloudtranscribe.store.TranscriptStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
internal class CloudUsageViewModel @Inject constructor(private val store: TranscriptStore) : ViewModel() {
	var usage by mutableStateOf<List<MonthUsage>?>(null)
		private set
	var loading by mutableStateOf(false)
		private set

	fun load() {
		if (loading) return
		loading = true
		viewModelScope.launch {
			try {
				usage = withContext(Dispatchers.IO) {
					val ids = store.recordingIds()
					val transcripts = ids.flatMap { runCatching { store.transcripts(it) }.getOrDefault(emptyList()) }
					val summaries = ids.flatMap { runCatching { store.summaries(it) }.getOrDefault(emptyList()) }
					CloudUsage.summarize(transcripts, summaries, ZoneId.systemDefault(), System.currentTimeMillis())
				}
			} finally {
				loading = false
			}
		}
	}
}

/** Argument order of `cloud_usage_month`; pure so the counters stay unit-tested without Android resources. */
internal fun usageArgs(u: MonthUsage): List<Any> =
	listOf(u.month.toString(), u.cloudTranscripts, CloudUsage.minutes(u.uploadedMs), u.requests, u.summaries)

/** Shown inside the collapsed-by-default 用量 card; computes only when the card is opened. Reads local records only. */
@Composable
internal fun CloudUsageSection(viewModel: CloudUsageViewModel = hiltViewModel()) {
	androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
		val usage = viewModel.usage
		when {
			usage == null -> CircularProgressIndicator()
			usage.all { it.cloudTranscripts == 0 && it.summaries == 0 } -> Text(stringResource(StringsRes.string.feature_settings_no_cloud_transcription_history_in_the_last, (CloudUsage.MONTHS).toString()), style = MaterialTheme.typography.bodySmall)
			else -> usage.forEach {
				val args = usageArgs(it)
				Text(androidx.compose.ui.res.stringResource(com.eva.feature_settings.R.string.cloud_usage_month, args[0], args[1], args[2], args[3], args[4]), style = MaterialTheme.typography.bodySmall)
			}
		}
		Text(
			androidx.compose.ui.res.stringResource(com.eva.feature_settings.R.string.cloud_usage_scope),
			style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}
