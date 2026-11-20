package com.eva.cloudtranscribe.live

/** Shared copy for the live-cloud cost/upload consent dialog (settings and the recorder chip). */
object LiveCloudConsentCopy {
	const val TITLE = "開啟進階即時雲端模式？"
	const val CONFIRM = "我了解費用與上傳，開啟"
	const val CANCEL = "取消"

	fun body(
		transcribeIntervalSec: Int,
		summaryIntervalSec: Int,
		summaryModel: String,
		enhanceContextQuality: Boolean = true,
	): String = buildString {
		append("錄音期間每 $transcribeIntervalSec 秒會把新錄到的音訊上傳到你設定的轉錄服務，並每 $summaryIntervalSec 秒把新增文字送到摘要服務（$summaryModel）。")
		append("會持續使用行動數據並產生 API 費用；服務慢或失敗時不會影響錄音存檔。")
		if (enhanceContextQuality) {
			append("已開啟「提高中英夾雜與斷句品質」：相鄰片段會短重疊約 3 秒（上傳量約增加一成），並可能多一次文字校正請求（不延遲顯示）。")
		}
	}
}
