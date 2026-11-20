package com.eva.cloudtranscribe.live

import com.eva.cloudtranscribe.R as StringsRes
import com.eva.utils.UiStrings

/** Shared copy for the live-cloud cost/upload consent dialog (settings and the recorder chip). */
object LiveCloudConsentCopy {
	val TITLE: String get() = UiStrings.get(StringsRes.string.data_cloudtranscribe_enable_advanced_live_cloud_mode, "開啟進階即時雲端模式？")
	val CONFIRM: String get() = UiStrings.get(StringsRes.string.data_cloudtranscribe_accept_upload_and_charges, "我了解費用與上傳，開啟")
	val CANCEL: String get() = UiStrings.get(StringsRes.string.data_cloudtranscribe_cancel, "取消")

	fun body(
		transcribeIntervalSec: Int,
		summaryIntervalSec: Int,
		summaryModel: String,
		enhanceContextQuality: Boolean = true,
	): String = buildString {
		append(UiStrings.get(StringsRes.string.data_cloudtranscribe_during_recording_new_audio_is_sent_to, "錄音期間每 %1\$s 秒會把新錄到的音訊上傳到你設定的轉錄服務，並每 %2\$s 秒把新增文字送到摘要服務（%3\$s）。", transcribeIntervalSec, summaryIntervalSec, summaryModel))
		append(UiStrings.get(StringsRes.string.data_cloudtranscribe_mobile_data_and_api_charges_may_apply, "會持續使用行動數據並產生 API 費用；服務慢或失敗時不會影響錄音存檔。"))
		if (enhanceContextQuality) {
			append(UiStrings.get(StringsRes.string.data_cloudtranscribe_bilingual_quality_enhancement_adds_about_three_seconds, "已開啟「提高中英夾雜與斷句品質」：相鄰片段會短重疊約 3 秒（上傳量約增加一成），並可能多一次文字校正請求（不延遲顯示）。"))
		}
	}
}
