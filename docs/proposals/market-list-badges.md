# 提案：錄音列表狀態標籤（逐字稿／摘要／未完成待辦）

## 參考依據
- **Plaud 官方說明**：找不到結果時「打開 Recordings 列表、選取錄音並查看狀態」，狀態會從 Processing／Generating 變成 Complete。https://support.plaud.ai/hc/en-us/articles/53793130069657-Transcribe-and-summarize （FAQ 與 Checkpoint 段）
- **我們的做法**：沒有使用 Plaud 的程式或介面。依 RecorderApp 現有的 `TranscriptStore` 與本機待辦（Room，唯讀）自行規劃，直接在列表上顯示，不用逐一點開。

## 使用者好處
在錄音列表一眼看出哪些錄音已經有逐字稿、摘要，正在處理中，或還有沒勾掉的待辦，不必逐一打開。

## 範圍（最小可行）
1. 錄音卡片在長度下方多一行小字標籤（沒有任何狀態時不顯示）：
   - 「逐字稿」：有成功或部分完成的逐字稿（離線或雲端）。
   - 「轉錄中」：有排隊中或進行中的轉錄（且還沒有可用逐字稿時才顯示）。
   - 「摘要」：有成功且非空白的摘要。
   - 「待辦 N」：未完成待辦數量 > 0。
2. 標籤在背景執行緒讀取，只讀列表中可見的錄音；回到列表（畫面恢復）時重新整理。
3. 讀取某段失敗就不顯示該段的標籤，不影響列表。

## 資料、隱私與費用
- 只讀本機資料，不呼叫雲端、不新增儲存、不改 Room。

## 邊界情況
- 在預覽或測試中沒有資料時，卡片外觀與現在完全相同。
- 大量錄音：一次最多讀 300 段（依目前排序的前 300 段），其餘不顯示標籤。
- 依逐字稿檔案修改時間快取，沒變就不重讀。

## 不在範圍內
- 不新增篩選或排序、不顯示進度百分比、不改點擊行為。

## 驗收測試（單元測試）
- 各種狀態組合得到正確標籤與順序；FAILED／CANCELLED 的逐字稿不算「逐字稿」。
- 「目前無重點」仍算有摘要；空白摘要不算。
- 沒有任何狀態時回傳空（卡片不多一行）。
- 300 段上限。

## 相關程式路徑
- `feature/recordings/.../recordings/composables/RecordingCard.kt`、`RecordingsInteractiveList.kt`、`RecordingsScreen.kt`、`RecordingsRoute.kt`
- `data/cloudtranscribe/.../store/TranscriptStore.kt`、`data/recordings/.../provider/RecordingActionItemsProvider.kt`

## 共通約束
- **目前只是規格，還沒實作**。合併這份文件不代表功能已完成。
- 不送任何資料到雲端；不修改任何既有資料。
