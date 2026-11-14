package com.eva.feature_player.cloud

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eva.cloudtranscribe.model.TranscriptRecord
import com.eva.cloudtranscribe.translate.SavedTranslation
import com.eva.cloudtranscribe.translate.TranslationLanguage
import com.eva.cloudtranscribe.translate.TranslationView

/** Translate button + view switcher above the transcript lines. Nothing is sent until the user confirms. */
@Composable
internal fun TranslationBar(
	record: TranscriptRecord,
	viewModel: TranslationViewModel,
	selectedLang: String?,
	onSelectLang: (String?) -> Unit,
	view: TranslationView,
	onView: (TranslationView) -> Unit,
) {
	LaunchedEffect(record.recordingId) { viewModel.bind(record.recordingId) }
	var pick by remember { mutableStateOf(false) }
	var confirmLang by remember { mutableStateOf<TranslationLanguage?>(null) }
	val available = viewModel.translations.filter { it.transcriptId == record.id }
	val current: SavedTranslation? = TranslationRules.forRecord(available, record, selectedLang)
	val blocked = TranslationRules.blockedReason(record)

	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
			if (viewModel.working) {
				val p = viewModel.progress
				Text("翻譯中…" + (p?.let { " ${it.first}/${it.second}" } ?: ""), style = MaterialTheme.typography.labelMedium)
				TextButton(onClick = viewModel::cancel) { Text("取消翻譯") }
			} else {
				TextButton(enabled = blocked == null, onClick = { pick = true }) { Text("翻譯") }
			}
			if (current != null) {
				available.forEach { t ->
					val lang = TranslationLanguage.entries.firstOrNull { it.tag == t.targetLang }
					FilterChip(t.targetLang == current.targetLang, { onSelectLang(t.targetLang) }, { Text(lang?.label ?: t.targetLang) })
				}
				FilterChip(view == TranslationView.ORIGINAL, { onView(TranslationView.ORIGINAL) }, { Text("原文") })
				FilterChip(view == TranslationView.TRANSLATION, { onView(TranslationView.TRANSLATION) }, { Text("譯文") })
				FilterChip(view == TranslationView.BILINGUAL, { onView(TranslationView.BILINGUAL) }, { Text("雙語") })
				TextButton(onClick = { viewModel.delete(current.targetLang); onSelectLang(null); onView(TranslationView.ORIGINAL) }) { Text("刪除譯文") }
			}
		}
		if (current != null && view != TranslationView.ORIGINAL) {
			Text(
				(if (TranslationRules.stale(current, record)) "原文已更新，譯文可能過期（不會自動重翻）。" else "") +
					"譯文僅供參考、唯讀；時間與跳轉都以原文為準。",
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.secondary,
			)
		}
		viewModel.message?.let { msg ->
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
				Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
				TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
			}
		}
	}

	if (pick) AlertDialog(
		onDismissRequest = { pick = false },
		title = { Text("翻譯成？") },
		text = {
			Column {
				TranslationLanguage.entries.forEach { lang ->
					TextButton(onClick = { pick = false; confirmLang = lang }) { Text(lang.label) }
				}
			}
		},
		confirmButton = {},
		dismissButton = { TextButton({ pick = false }) { Text("取消") } },
	)
	confirmLang?.let { lang ->
		AlertDialog(
			onDismissRequest = { confirmLang = null },
			title = { Text("翻譯成${lang.label}？") },
			text = {
				Text("會把這份逐字稿的文字（逐句編號、分段）送到你設定的摘要服務，使用已儲存的 API Key；音訊檔不會因翻譯再傳一次。" +
					"翻譯是另一次文字請求，有 token／額度成本。任何一段沒有逐句對齊就整次失敗、不儲存。" +
					(if (lang == TranslationLanguage.ZH_HANT) "若原文已是繁體中文，譯文可能沒有明顯差異。" else ""))
			},
			confirmButton = {
				TextButton({
					confirmLang = null
					viewModel.translate(record, lang)
					onSelectLang(lang.tag)
					onView(TranslationView.BILINGUAL)
				}) { Text("送出翻譯") }
			},
			dismissButton = { TextButton({ confirmLang = null }) { Text("取消") } },
		)
	}
}
