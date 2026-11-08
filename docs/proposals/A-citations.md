# 提案 A：重點條目附來源時間（點了跳到逐字稿）

## 參考依據
- **Notion 官方文件**：摘要中有指向逐字稿的引用，滑過會顯示被引用的片段，點擊會跳到逐字稿的對應行。https://www.notion.com/help/ai-meeting-notes （「Follow the transcript with speaker labels & citations」段）
- **我們的做法**：沒有使用 Notion 的程式或介面。下面的設計是依 RecorderApp 現有的資料結構自行規劃。

## 使用者好處
看到某條重點，可以直接回到原話和音檔位置核對，減少誤信模型自行生成的內容。

## 範圍（最小可行）
1. 錄音後產生摘要（`CloudSummaryWorker`）時，把逐字稿改成帶編號的片段送出，例如 `[S3 03:12–03:40] 文字`，並要求模型在每條重點後面附上 `〔S3〕` 這類片段編號。
2. 解析回覆時逐條驗證：
   - 片段編號必須存在。
   - **內容要對得上**：重點裡至少要有一個詞（長度 ≥2 的中文詞或英文 token）出現在被引用的片段原文中，否則判定為「引用不足」，不顯示時間連結。
   - 驗證失敗或沒有引用的條目，只顯示文字，不顯示時間。
3. 新增 `SummaryRecord.citations: List<Citation(lineIndex, segmentIndex, startMs, endMs)>`，預設空白，舊資料可以照常讀取。
4. 介面上，條目後面顯示 `03:12` 這種小標籤，點了就切到對應的逐字稿和行，並跳到該位置播放。

## 相依性（重要）
- 播放頁目前**沒有顯示摘要的地方**：摘要只出現在「雲端轉錄」對話框（`feature/player/.../cloud/CloudTranscriptsDialog.kt`）。A 需要一個最小的「摘要區」，或者先做在對話框裡（建議先做在對話框）。
- 不依賴 PR #1、#2。

## 資料、隱私與費用
- 不增加呼叫次數：仍然是原本那一次摘要呼叫。
- **但不是免費的**：片段編號會讓送出的 prompt 變長，要求附引用也會讓回覆變長，兩者都會增加 token 用量。
- 舊的摘要沒有引用。要補上引用必須重新呼叫一次，而且只能由使用者手動觸發、並在確認視窗裡說明會產生費用。
- 送出的資料跟現在一樣（逐字稿文字），不會多送其他資料。

## 邊界情況
- 離線逐字稿、即時重點（`keypoints-live-*`）沒有可靠的對應片段，**不支援**，顯示為「無引用」。
- 有分割過的片段（`parts`）：用子片段的時間。
- 失敗或疑似幻覺的片段（FAILED/suspicious）不能被引用。
- 模型編造不存在的編號：該條目不顯示時間。

## 不在範圍內
- 不在錄音中即時加引用。
- 不做說話者標記。
- 不回頭替舊摘要自動補引用。

## 驗收測試（單元測試＋人工）
- 能解析 `〔S3〕` 和 `〔S3,S5〕` 兩種格式；不存在的編號會被拒絕。
- 內容對不上的引用會被拒絕；對得上的會保留。
- 舊格式的 `SummaryRecord` JSON 能正常讀取，`citations` 為空。
- 人工：點時間標籤會跳到正確位置；沒有引用的條目不顯示時間。

## 相關程式路徑
- `data/cloudtranscribe/.../work/CloudJobs.kt`（`CloudSummaryWorker`）
- `data/cloudtranscribe/.../engine/CloudTranscriptionEngine.kt`（`Prompts.summary`）
- `data/cloudtranscribe/.../model/CloudModels.kt`（`SummaryRecord`、`SegmentResult`）
- `data/cloudtranscribe/.../model/PlaybackTranscripts.kt`
- `feature/player/.../cloud/CloudTranscriptsDialog.kt`、`SavedTranscriptPanel.kt`

## 已知限制
驗證只能確認「引用的片段裡有出現相關的詞」，**沒辦法證明這條重點的意思真的出自該片段**。這一點會寫進介面的說明文字。

## 共通約束（所有提案都要遵守）
- **目前只是規格，還沒實作**。合併這份文件不代表功能已完成。
- 預設的重點整理 prompt 維持精簡；沒有重點時只回「目前無重點」，不強迫列出分類或待辦。
- 雲端功能預設關閉。API Key 一律由使用者明確選擇（轉錄 Key 與摘要 Key 分開）；任何把資料送到雲端的動作，都要先有使用者同意或手動確認。
- 時間戳只能來自真實的雲端分段（`SegmentResult.startMs/endMs`），不推測時間，也不編造說話者。
- 每段錄音的資料彼此隔離（參考 v1.7.14 的修正），不會互相影響。
- 原始逐字稿和摘要（`TranscriptRecord`/`SummaryRecord`）視為不可修改；任何修訂都另存成新的一筆，不覆蓋舊的。
- 基準分支：`feature/drive-auto-upload` @ c7f5e7f（v1.7.14）。
- 現有的 PR #1（逐字稿搜尋）、#2（全域會前備註）都還沒合併，也沒有被修改。
