package com.eva.feature_settings.composables.transcribe

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

internal fun gib(bytes: Long?): String = bytes?.let { String.format(Locale.TAIWAN, "%.2f GiB", it / 1073741824.0) } ?: "未知"

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
                    Text("儲存空間：可用 ${gib(snapshot.diskFree)} / ${gib(snapshot.diskTotal)}")
                    Text("RAM：可用 ${gib(snapshot.ramFree)} / ${gib(snapshot.ramTotal)}")
                }
                TextButton(onClick = onRefresh) { Text("更新") }
            }
            resourceWarning(snapshot)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

internal fun resourceDescription(snapshot: ResourceSnapshot, m: ModelResources): String = buildString {
    if (m.installed) append("已安裝 · 新增下載空間 0 GiB。")
    else {
        append("未安裝")
        if (m.already > 0) append("；已保留 ${gib(m.already)}，尚需 ${gib(m.download - m.already)}")
        append("；含暫存／保留空間，共需可用 ${gib(m.diskNeeded)}。")
        append(when (ResourcePolicy.diskEnough(snapshot.diskFree, m.diskNeeded)) { true -> "儲存空間足夠。"; false -> "儲存空間不足，請先釋放空間。"; null -> "儲存空間未知，下載時會再次檢查。" })
    }
    append("\n模型估計 RAM 需求：${gib(m.ramNeeded)}")
    if (m.ramNeeded == null) append("（此模型沒有可靠估計）")
    else append(when (ResourcePolicy.ramEnough(snapshot.ramFree, snapshot.lowMemory, m.ramNeeded, snapshot.is64, m.weights)) {
        true -> "；目前足夠（估計）。"
        false -> if (!snapshot.is64 && (m.weights ?: 0) >= ResourcePolicy.LARGE_MODEL) "；不支援：需要 64 位元。" else "；目前 RAM 不足／系統低記憶體。"
        null -> "；目前 RAM 狀態未知。"
    })
}
