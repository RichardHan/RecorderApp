package com.eva.feature_settings.composables.transcribe

import com.eva.feature_settings.R as StringsRes
import com.eva.utils.UiStrings
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.eva.transcribe.domain.models.STTModel
import com.eva.transcribe.resources.*
import java.util.Locale

internal fun gib(bytes: Long?): String = bytes?.let { String.format(Locale.TAIWAN, "%.2f GiB", it / 1073741824.0) } ?: UiStrings.get(StringsRes.string.feature_settings_unknown, "未知")

/** Composition + STARTED lifecycle scoped: no worker, no background monitor or IO on main. */
@Composable
internal fun rememberResources(models: List<STTModel>, refresh: Int): ResourceSnapshot {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var snapshot by remember { mutableStateOf(ResourceSnapshot()) }
    LaunchedEffect(lifecycle, models, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            pollResources(read = { ResourceReader.read(context, models) }, emit = { snapshot = it })
        }
    }
    return snapshot
}

@Composable
internal fun TranscriptionResourcePanel(snapshot: ResourceSnapshot, onRefresh: () -> Unit) {
    Card {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(StringsRes.string.feature_settings_storage_available_of, (gib(snapshot.diskFree)).toString(), (gib(snapshot.diskTotal)).toString()))
                    Text(stringResource(StringsRes.string.feature_settings_ram_available_of, (gib(snapshot.ramFree)).toString(), (gib(snapshot.ramTotal)).toString()))
                }
                TextButton(onClick = onRefresh) { Text(stringResource(StringsRes.string.feature_settings_refresh)) }
            }
            resourceWarning(snapshot)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

internal fun resourceDescription(snapshot: ResourceSnapshot, m: ModelResources): String = buildString {
    if (m.installed) append(UiStrings.get(StringsRes.string.feature_settings_installed, "已安裝"))
    else {
        append(UiStrings.get(StringsRes.string.feature_settings_not_installed, "未安裝"))
        if (m.already > 0) append(UiStrings.get(StringsRes.string.feature_settings_more_needed, " · 尚需 %1\$s", gib(m.download - m.already)))
        append(UiStrings.get(StringsRes.string.feature_settings_requires_free, " · 需可用 %1\$s", gib(m.diskNeeded)))
        if (ResourcePolicy.diskEnough(snapshot.diskFree, m.diskNeeded) == false) append(UiStrings.get(StringsRes.string.feature_settings_insufficient_storage, " · 儲存空間不足"))
    }
    m.ramNeeded?.let { need ->
        append(UiStrings.get(StringsRes.string.feature_settings_nram_required, "\nRAM 需求 %1\$s", gib(need)))
        if (ResourcePolicy.ramEnough(snapshot.ramFree, snapshot.lowMemory, need, snapshot.is64, m.weights) == false)
            append(if (!snapshot.is64 && (m.weights ?: 0) >= ResourcePolicy.LARGE_MODEL) UiStrings.get(StringsRes.string.feature_settings_unsupported_requires_64_bit, " · 不支援：需要 64 位元") else UiStrings.get(StringsRes.string.feature_settings_insufficient_available_ram, " · 目前 RAM 不足"))
    }
}
