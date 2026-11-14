# 提案：背景雲端轉錄／摘要完成時通知（可選，預設關）

## 參考依據
- **Otter 官方說明**：錄音處理完成後會寄 email，且「如果有開啟」會透過手機 App 推播通知。https://help.otter.ai/hc/en-us/articles/360048322493-Transcription-processing-time-FAQ （開頭 Tip）
- **Plaud 官方說明**：錄音結束後在背景轉錄、摘要，完成時送出帶有標題的通知，點了直接打開那份筆記。https://support.plaud.ai/hc/en-us/articles/63429935495065-Automatic-transcription-and-summaries-for-v4-0-x-or-later （How does it work）
- **我們的做法**：沒有使用上述產品的程式。RecorderApp 的雲端轉錄與摘要已經在 WorkManager 背景執行，但完成時沒有任何提示；這個提案只在本機發一則通知。

## 使用者好處
開了「錄音完成後自動雲端轉錄」後，不用一直回來看；完成或失敗時收到通知，點一下直接進到該錄音的播放頁。

## 範圍（最小可行）
1. 雲端 AI 設定新增開關「背景雲端轉錄／摘要完成時通知」，**預設關**。
2. 開啟後：
   - 雲端轉錄工作結束時：「『標題』雲端轉錄完成」／「部分完成（有段落失敗）」／「失敗」。
   - 錄音後摘要工作結束時：「『標題』摘要完成」＋第一條重點（最多 60 字）／「摘要失敗」。
   - 使用者取消的工作不通知。
3. 點通知＝用既有的播放頁 deep link（`NavDeepLinks.audioPlayerDestinationUri`）開啟該錄音。
4. 鎖定畫面只顯示「RecorderApp：雲端工作完成」，不顯示標題與重點內容（`VISIBILITY_PRIVATE` 加公開版本）。
5. 沒有通知權限或系統關閉通知時，安靜略過，不會跳出權限要求。

## 資料、隱私與費用
- 通知只在本機發出，不送任何資料、不增加 API 呼叫、不改 Room。

## 邊界情況
- 同一段錄音的轉錄與摘要通知使用不同 id，不互相覆蓋；同一種工作重跑會覆蓋前一則。
- 錄音已被刪除而找不到標題：顯示「錄音」。

## 不在範圍內
- 不加 email／Slack 等外部通知；不在錄音中通知；不顯示進度。

## 驗收測試（單元測試）
- 各狀態的通知文字（成功、部分、失敗、摘要＋第一條重點截斷、目前無重點）。
- 設定預設關；舊設定 JSON 讀取後仍為關。
- 取消不通知（狀態判斷）。

## 相關程式路徑
- `data/cloudtranscribe/.../work/CloudJobs.kt`（`CloudTranscribeWorker`、`CloudSummaryWorker`）
- `data/cloudtranscribe/.../model/CloudModels.kt`（`CloudSettings`）
- `feature/settings/.../composables/cloudai/CloudAiSettings.kt`
- `core/utils/.../NavDeepLinks.kt`

## 共通約束
- **目前只是規格，還沒實作**。合併這份文件不代表功能已完成。
- 雲端功能預設關閉；這個開關也預設關閉。
