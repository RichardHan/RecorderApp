# 提案：逐字稿＋重點匯出（TXT／Markdown，有真實時間才給 SRT）

> 狀態：**尚未實作，等待使用者選 agent 開工。** 這個 PR 只有規格文件，沒有程式碼、設定或 workflow 變更。
> 基準：`feature/drive-auto-upload` @ c190555。來源存取日期：2026-11-09（台北時間）。
## 參考來源
- Otter「Export conversations」：https://help.otter.ai/hc/en-us/articles/360047733634-Export-conversations ：可匯出 TXT、DOCX、PDF、SRT，並可選擇是否顯示時間戳、說話者，或合併段落。手機上可從 Transcript 頁的選單匯出。
- Otter「Create captions & subtitles」：https://help.otter.ai/hc/en-us/articles/11742706003735-Create-captions-subtitles-for-your-video ：SRT 用於字幕。

**來源產品的功能** = 多格式匯出加選項。**我們的版本** = 本機產生檔案，透過 Android 分享面板或 SAF 存檔，只做三種純文字格式，不做 DOCX／PDF。

## 現況與缺口
目前只有書籤可匯出成 CSV（`data/bookmarks/.../BookMarksToCsvFileConvertor.kt`），逐字稿和重點沒有匯出功能，使用者只能手動複製。

## 最小範圍
- 播放頁選單 >「匯出文字」> 選格式：
  - **TXT**：標題、錄音日期、時長、重點（若有）、逐字稿。
  - **Markdown**：同上，用 `#`／`-` 結構；書籤列成「- [mm:ss] 書籤文字」。
  - **SRT**：**只有在逐字稿保存了真實分句時間時才顯示**（依賴 #21「離線逐字稿保存分句時間」或等效資料）。沒有時間資料時隱藏這個選項，不估算、不平均切時間。
- 選項：是否包含重點、是否包含書籤、是否顯示「Speaker N:」標籤（保留模型輸出原樣，不改名）。
- 輸出走 `ACTION_SEND`（分享）或 `ACTION_CREATE_DOCUMENT`（存檔），沿用現有 `ExportURIProvider` 的 FileProvider 作法。

## 資料／架構
- 新增純 Kotlin `TranscriptExportFormatter`（格式化，可單元測試）加上一個 use case，讀 `TranscriptStore`、書籤和中繼資料。
- 不新增資料表、不遷移；暫存檔放 `cacheDir/exports`，分享完成或下次啟動時清除。

## 隱私／成本
完全在本機進行，不呼叫任何 API，不上傳。分享目的地由使用者在系統面板自己選。

## 邊界情況
- 逐字稿仍在轉錄中：匯出目前已保存的內容，並在檔頭註記「轉錄未完成」。
- 空逐字稿：提示「沒有可匯出的逐字稿」，不產生空檔。
- 檔名：`<錄音名>_<yyyyMMdd-HHmm>.<ext>`，過濾非法字元。

## 驗收測試
1. 單元：TXT／MD 固定樣本輸出（golden file）。
2. 單元：SRT 序號、`HH:MM:SS,mmm --> ` 格式、時間單調遞增；沒有時間資料時 formatter 拒絕產生 SRT。
3. 單元：檔名過濾。
4. 儀器測試：FileProvider URI 可讀，暫存檔有清理。

## 依賴
SRT 依賴 #21 或等效的真實分句時間；TXT／MD 沒有依賴。

## 非目標
DOCX／PDF、批次匯出、自動寄送或上傳到外部服務、匯出音訊（已有分享錄音功能）。
