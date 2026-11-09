# 提案：決議／決定清單（與待辦分開，手動觸發）

> 狀態：**規格提案，尚未實作，等待使用者選擇 agent；勿合併視為功能完成。**
> 這個 PR 只有規格文件，沒有程式碼、設定或 workflow 變更。
> 基準：`feature/drive-auto-upload` @ 8e58564。來源存取日期：2026-11-09（America/Chicago 時區）。

## 參考來源（公開官方說明）
- MeetGeek「Highlights & Summaries」：https://support.meetgeek.ai/en/articles/6021900-highlights-summaries （2026-08-10 更新；本頁於 2026-11-09（America/Chicago 時區） 開啟）
  - 文件把 AI highlight 分成四類：Decision（承諾／決定）、Concern、Fact、Task。
  - Decision 範例是「團隊同意接受…」這類明確承諾，和 Task（待辦／期限）分開。
- MeetGeek「Discover Post-Meeting Features」：https://support.meetgeek.ai/en/articles/8021140-discover-post-meeting-features
  - 同樣列出 Decision / Concern / Fact / Task 四種自動標籤。

**來源產品的功能** = 會後自動打 highlight 標籤，並可推到 Slack／CRM。  
**我們的版本（獨立改編）** = 錄音結束後，**由使用者手動觸發**一次「抽出決議」；結果存成本機清單。不自動推送外部服務，不推測負責人。

## 與既有提案的差別（避免重複）
- #28／#34 待辦清單：處理「要做的事」。本提案處理「已經做成的決定／承諾」。
- PR3 重點來源時間：替現有重點條目附引用。本提案是**獨立的決議清單**。
- PR4／#33 摘要格式、PR7 問答、#29／#35 章節：不重疊。
- 預設精簡摘要不變；決議抽出是另外一次、手動的請求。

## 使用者問題
會後常分不清「決定了什麼」和「還要做什麼」。摘要把兩者混在一起，之後不好對帳。

## 最小範圍
- 播放頁「決議」小卡（預設收合）：按鈕「抽出決議」→ 確認對話框（說明會把逐字稿送到目前雲端服務、會用 token）→ 產生清單。
- 每項：文字（可編輯）、選填「來源時間」（**只能**來自真實分句時間；沒有時間資料就不顯示時間）、完成狀態不需要（決議不是待辦）。可刪除、可手動新增。
- 模型契約：輸入帶**句子編號**的逐字稿。模型只回 `[{text, start_sentence_index?}]`。App 用本機時間表換成 `start_ms`。索引越界或缺少文字就整批丟棄。**禁止模型輸出時間戳或說話者姓名。**
- 提示強調：只列出逐字稿裡**明確說出的決定／共識／承諾**；猜測、待辦、 aspirational 語句不要；沒有就回空陣列。

## 資料模型（Room，需遷移）
- `recording_decisions`：`id`、`recording_id`（FK CASCADE）、`text`、`start_ms`（nullable）、`source_sentence_index`（nullable）、`source`（`extracted`|`manual`）、`created_at`、`transcript_revision`。
- 逐字稿 revision 改變時標「可能已過期」，不自動重算。

## API／成本／隱私
- 沿用現有雲端摘要／相容端點與金鑰，不新增服務。每次手動觸發多送約一份逐字稿的 token，**有成本**。預設不自動跑。
- 不把逐字稿或模型回應寫進 log。雲端預設關閉的行為不變。

## 邊界情況
- 無逐字稿／轉錄中：按鈕停用。
- 取消或失敗：不寫入部分結果。
- 與待辦並存：同一句話若既像決議又像待辦，使用者可兩邊都留；不自動去重跨表。
- 離線：不能抽出；已存決議仍可看、編輯、跳轉。

## 驗收測試
1. 單元：解析與驗證（空陣列、越界、缺 text、捏造時間欄位被拒）。
2. 單元：句子編號 → 真實 `start_ms`；無時間表時 `start_ms=null` 仍可保存文字。
3. 單元：取消不寫 DB。
4. Room 遷移＋刪除錄音 CASCADE。
5. 手動：點有時間的決議會跳到該句開始（誤差在一句內）。

## 依賴
有時間跳轉較佳時依賴 #21（已合併）的分句時間；沒有時間仍可只存文字。

## 非目標
Concern／Fact／KPI、自動標籤、推送到 Slack／CRM、推測負責人、自動寄信、錄音中即時抽出。
