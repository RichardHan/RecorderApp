# 提案：把會議資訊持續送到 Slack（使用者設定的 opt-in）

> 狀態：**規格提案，尚未實作，等待使用者選擇 agent；勿合併視為功能完成。**
> 這個 PR 只有規格文件，沒有程式碼、設定或 workflow 變更。
> 基準：`main` @ 3903947。來源存取日期：2026-11-14（台北時間）。

本功能是使用者於 2026-11-14 明確表示「可以把 meeting 的資訊一直送 Slack 我覺得不錯」之後才提案。這只開放**這一個、由使用者設定並預設關閉的 opt-in 外送管道**；不因此把郵件、日曆、任務系統或其他自動外送一併做進來。

## 參考來源（公開官方說明）

### Slack（開發者文件）
- Incoming Webhooks：https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/ （本頁於 2026-11-14（台北時間） 開啟）
  - 建立 webhook 會得到單一頻道、單一使用者綁定的 URL；URL **含密鑰**，不可公開或寫進版控。Slack 會主動撤銷洩漏的密鑰。
  - `POST` JSON（至少要有 `text`）；可用 Block Kit。**不能覆寫安裝時選的頻道、使用者名稱或圖示。**
  - **Webhook 回應不回傳訊息 `ts`。** 文件雖說可用 `thread_ts` 回覆討論串，但必須先用別的方法（Events API、`conversations.history` 等）取得 `ts`，而那些方法都需要 token，不是純 webhook 能做的。
  - **Webhook 不能在送出後刪除訊息。** 若需要較完整的訊息生命週期（含刪除、編輯），應改用 `chat.postMessage`。
- 速率限制：https://docs.slack.dev/apis/web-api/rate-limits （本頁於 2026-11-14（台北時間） 開啟）
  - Incoming webhooks：**每秒 1 則**，短時間突發可被容忍。
  - 透過 `chat.postMessage`、webhook 或其他管道發到同一頻道，原則上也是 **每頻道每秒 1 則**。
  - 超過限制時，HTTP API（含 webhook）回 `HTTP 429`，並帶 `Retry-After`（秒數）。持續超限有被永久停用的風險。Slack 建議以「任一 API 每秒約 1 次」來設計。
- `chat.postMessage`：https://docs.slack.dev/reference/methods/chat.postMessage （本頁於 2026-11-14（台北時間） 開啟）
  - `text` 建議不超過 **4,000** 字元；超過約 **40,000** 字元會被截斷，並可能在回應標 `message_truncated`。有 `blocks` 時，限制依區塊類型而定。
  - 提供 `thread_ts` 即可在父訊息下回覆。成功回應含 `ts`，後續才能編輯或串回覆。
- `chat.update`：https://docs.slack.dev/reference/methods/chat.update （本頁於 2026-11-14（台北時間） 開啟）
  - 用 bot 或 user token（`chat:write`）更新已送出的訊息。Rate limit **Tier 3（每分鐘 50+ 次）**。Webhook **沒有**對應的更新能力。

### 其他產品（只作行為參考，不複製 UI／文案）
- Claude Meetings（Anthropic Help Center）：https://support.claude.com/en/articles/18325154-use-claude-meetings （本頁於 2026-11-14（台北時間） 開啟）
  - 會議中可使用「已連線的工具」，文件示例包含「像是送出一則 Slack 訊息」；需當事人同意，第一次詢問，之後可記住。這是會議中的工具呼叫，不是本機錄音 App 的連續同步。
  - **我們的改編**：不加入 Google Meet、不自動加入通話、不讀 Slack；只在使用者開啟後，把**本機已產生的會議文字**推到他指定的頻道。
- Otter「Connect Slack to Otter.ai」：https://help.otter.ai/hc/en-us/articles/13627121851799-Connect-Slack-to-Otter-ai （May 29, 2026 Updated；本頁於 2026-11-14（台北時間） 開啟）
  - 會後可把摘要發到指定頻道（「Post to Slack」），定期會議可設自動張貼。偏會後一次發送，不是錄音中連續更新。
- Fireflies Slack 整合：https://guide.fireflies.ai/articles/1671003178-how-to-set-up-the-fireflies-slack-integration （本頁於 2026-11-14（台北時間） 開啟）
  - 把會議筆記推到頻道或 DM；新版版面是**主訊息放重點，完整筆記與待辦放在討論串回覆**。連線後可「Insert Sample Data／Test Push Flow」測試。預設可能對每次會議都送，也可用規則篩選。
  - **我們的改編（v2）**：若有 bot token，用「一則父訊息 + 討論串更新」接近這種版面；v1 webhook 做不到穩定的討論串，見下文取捨。
- Fathom Slack 產品頁：https://www.fathom.ai/integrations/slack （本頁於 2026-11-14（台北時間） 開啟）
  - 可把摘要、待辦、highlights 自動發到頻道或 DM。主要是會後推送（另有擷取 Slack Huddle，與本提案無關）。

## 與 RecorderApp 現況的關係
- 已有：即時重點、#34 本機待辦、#35 章節、#30 文字匯出、雲端同意流程、`EncryptedKeyStore`（AndroidKeyStore AES-GCM，明文從不寫 log）。
- 尚未有任何 Slack／webhook 外送模組。
- #36 決議、#37–#41 等若尚未合併，結束訊息只附「當時已經存在」的區塊，不把未實作功能當成已上線。

## 使用者問題
會議進行中，人在 Slack 頻道裡的同事看不到現場重點，只能等結束後再手動貼。

## 最小範圍（v1）與後續（v2）

### v1（建議先做）：Incoming Webhook URL
- 使用者在 Slack 依官方步驟建立 Incoming Webhook，把 URL 貼進 App。
- URL 用既有 `EncryptedKeyStore` 模式儲存（獨立 prefs 檔與 Keystore alias，例如 `slack_webhook_secure`／`recorderapp_slack_webhook`；排除備份）。**明文永不寫 log、永不進崩潰報告或 Analytics。**
- 傳送：對 webhook URL 做 `HTTPS POST` JSON。無法改頻道（由建立 webhook 時選定）。
- **取捨（必須在設定頁寫清楚）**：
  - Webhook **不回傳 `ts`**，因此 v1 **不能可靠地開討論串，也不能編輯同一則訊息。**
  - 「持續送」在 v1 =：每個錄音先送一則「開始」父向訊息，之後每次符合節流條件的新重點再**另發一則新訊息**（不是編輯舊的）。結束時再送一則「結束摘要」。
  - 為避免洗版：預設節流 **至少 N 分鐘才再送一則重點更新**（建議 N＝3，可設 2–10），而且**只送與上次已送出內容的 diff**（新增或改寫的條目），不是每次重貼整份清單。
  - 仍須遵守 Slack「每頻道每秒 1 則」；我們的節流遠嚴於此。遭遇 429 時依 `Retry-After` 等候再試。

### v2（可選，不阻擋 v1）：Slack OAuth bot token
- 使用者安裝自建 Slack App，授權 `chat:write`（必要時 `incoming-webhook`），把 bot token 同樣加密存放。
- 用 `chat.postMessage` 建立一則父訊息（得到 `ts`），之後以 `thread_ts` 在討論串更新重點，或用 `chat.update` 更新同一則「進行中」訊息（二選一，設定項；預設討論串，較接近 Fireflies 的「主訊息 + 串內細節」）。
- v2 仍是 opt-in，預設關閉。本提案的實作可先只交 v1，v2 列為明確後續。

## 開關與設定 UI
- **總開關預設 OFF。** 沒有有效 webhook（或 v2 token）時，即使打開也要拒絕傳送並提示。
- 設定位置：設定 → 雲端／AI 分頁新增「Slack 同步」區塊（靠近既有雲端同意與 live 重點設定）。
- 內容：
  - 貼上／更換／清除 webhook URL（輸入為密碼欄，永不回顯完整 URL）。
  - 可選顯示名稱（只存在本機，例如「#meeting-notes」），供同意畫面與錄音畫面顯示；**App 無法從純 webhook URL 查頻道名**，必須讓使用者自己填。
  - 「測試傳送」按鈕：送一則固定、不含任何錄音內容的測試句（例如「RecorderApp 測試：Slack 連線正常。」）。成功／失敗要顯示 Slack 狀態碼或可理解錯誤（`invalid_payload`、`no_service` 等），不含 URL。
  - 要送哪些區塊（可多選，預設如下）：
    - 錄音開始通知（預設開）
    - 即時重點更新（預設開；見下文規則）
    - 定期逐字稿摘錄（**預設關**，必須再確認一次）
    - 結束摘要（預設開；可附 #34 待辦、#35 章節、已存在的決議清單）
  - 重點更新最短間隔 N 分鐘。
- **錄音畫面每則錄音可覆寫**：預設跟隨總開關；可對「這一則」暫時關閉或開啟（未設定 webhook 時不能開）。
- 第一次把總開關打開、或第一次在錄音畫面開啟時：全螢幕同意頁，明確寫出：
  - 會送到哪個目的地（使用者填的頻道名稱）；
  - 會送出哪些文字（重點／可選逐字稿摘錄／結束摘要）；
  - **參與者的語音轉成的文字會離開這台裝置、進入該 Slack 工作區**；
  - **不會上傳音訊檔**；
  - 可隨時在設定關閉並清除 URL。
  - 必須點「同意並啟用」才會開始送。關閉後再打開需重新同意。
- 錄音進行中若 Slack 同步為開：錄音畫面顯示清楚指示（小圖示或文字「正在同步到 Slack：#…」），暫停或已關閉時消失。

## 要送什麼、何時送

### 開始
- 一則短訊：錄音標題（若有）、開始時間（裝置本地時區＋標明時區）、狀態「進行中」。不含逐字稿。

### 即時重點（「一直送」的核心）
- 只在 **重點清單相對上次成功送出的版本有實質新增或修改** 時才排隊。
- 若模型輸出「目前無重點」（與 `LiveCloudSettings.NO_KEY_POINTS` 相同）：**不要發到 Slack**，也不要用它蓋掉頻道裡已送出的重點。
- 空白、重複垃圾、`RepetitionGuard` 判定異常的內容：不送。
- 受 N 分鐘節流；期間若有多次更新，到期只送**最新 diff**（合併），避免積壓很多則幾乎相同的訊息。
- 訊息本文建議含「自上次更新後的新重點」條列；v1 可附一行「完整清單以 App 為準」。長度超過建議的 4,000 字元就截斷並註明「已截斷」。

### 逐字稿摘錄（opt-in，預設關）
- 若開啟：每隔較長間隔（建議 ≥5 分鐘）送出**新定稿句子**的摘錄，不是重貼全文。需在同意畫面單獨勾選。

### 結束
- 錄音停止且已有最終／當下重點時送一則結束訊息：精簡摘要、（若資料庫已有）待辦、章節標題列表、決議；並註明錄音已結束。
- 若使用者取消錄音或沒有任何可送內容：可送一則「已結束，沒有可分享的重點」，或選擇不送（設定項，預設不送空摘要）。

### 暫停／停止／雲端關閉
- **暫停錄音**：停止產生新的重點更新（沒有新音訊就不該有新內容）；不送「暫停」洗版訊息（除非使用者開啟「狀態通知」，預設關）。恢復後，節流計時繼續。
- **停止**：排入結束訊息；重試隊列只清這一則錄音未送出的更新，改送結束包（避免停止後還跳出舊的中場 diff）。
- **雲端／即時重點未開啟**：若使用者只開了 Slack 但沒有重點來源，開始通知仍可送（若有勾選），重點更新不送，並在設定說明「即時重點需要已開啟的雲端摘要」。結束時若已有本機摘要／待辦仍可送。
- **總開關或本則覆寫中途關閉**：立即停止新的傳送；隊列中未送出的更新丟棄（已送出的 Slack 訊息不試圖刪除——v1 也刪不了）。畫面上的指示關閉。

## 隊列、重試、去重、工作階段隔離
- 使用 WorkManager 將「待送訊息」持久化：`recording_id`、`kind`（start／keypoints_diff／transcript_excerpt／end／test）、`payload_hash`、`created_at`、`attempt`。
- 指數退避，並遵守 `Retry-After`。對 `invalid_payload`、`no_service`、`action_prohibited` 等官方建議「不要原樣重試」的錯誤：停止該則並在 UI 顯示，要求使用者修正。
- **去重**：相同 `recording_id + kind + payload_hash` 只送一次。
- **工作階段隔離（硬規則）**：隊列項目必須帶目前錄音的穩定 id。新錄音開始時，**不得**再送出上一則錄音的重點或逐字稿。測試按鈕使用 `kind=test`，與任何錄音無關。
- 裝置離線：排隊，恢復網路後再送；結束訊息可在停止後補送。

## 隱私、失敗、成本
- 不送音訊、不送 API 金鑰、不送 webhook URL。
- 可選「遮罩」：送出前對重點／摘錄做簡單的本機處理（例如把像電子郵件或長數字的字串換成 `[已隱藏]`）。預設關；開啟時在同意畫面說明。不做聲紋或自動認人。
- 失敗：錄音畫面非侵入式（例如小條「Slack 同步失敗，稍後重試」），可點進詳情。不要用會打斷錄音的對話框。
- 成本：呼叫 Slack API／webhook **本身不向 Slack 按則收費**（以官方公開文件為準，實作時須再核對）。但重點內容來自既有雲端摘要，那些請求的 token／額度成本不變。Slack 同步不另外打模型。

## 資料模型
- 加密儲存：webhook URL（及 v2 token）。
- DataStore：總開關、同意時間戳、頻道顯示名、勾選的內容類型、N 分鐘、遮罩、本則覆寫偏好不必跨錄音持久化（除非使用者明確「記住我的選擇」）。
- Room 小表 `slack_outbox`（見上）與 `slack_sent`（最後成功的 summary hash、start 是否已送）。錄音刪除時 CASCADE。

## 程式路徑（實作指引，本 PR 不寫程式）
- 設定 UI：`feature/settings/…/cloudai/` 既有雲端／AI 分頁。
- 新模組 `data/slack`：Webhook 用戶端、payload 組裝（Block Kit 可選，但 `text` 必填作通知備援）、隊列與 WorkManager worker、加密儲存。
- Hook：即時重點在 `LiveCloudSession`／摘要接受路徑（與 `LiveCloudSettings.acceptSummary` 之後）發出「可能有更新」；錄音開始／暫停／停止在 `RecorderActionHandler`／`CloudJobs` 附近發出生命週期事件。Slack 模組只消費這些事件，不把 Slack 密鑰傳進雲端轉錄程式碼。

## 驗收測試
1. 總開關預設關閉時，開始錄音不會對網路發出任何 hooks.slack.com 請求（儀器測試攔截）。
2. 未同意前按「啟用」不會儲存「已啟用」，也不會送出。
3. 測試按鈕送出不含錄音內容的固定句；log 中沒有 URL 明文。
4. 「目前無重點」不產生 Slack 工作項目；先前已送重點不被這次空結果撤回或覆寫。
5. 節流：三分鐘內連續三次重點變更，只送出最後一次 diff（外加開始／結束若適用）。
6. 新錄音開始後，隊列裡上一則錄音的 keypoints_diff 不得被送出。
7. 429 回應會等到 `Retry-After` 再重試。
8. `invalid_payload` 不會無窮重試；UI 顯示錯誤。
9. 離線停止錄音：恢復網路後仍會補送結束訊息（若有可送內容）。
10. 中途關閉同步：不再送新訊息；已在 Slack 的訊息保持不變（v1）。
11. 單元：組裝後的 `text` 不超過 4,000 字元建議上限（超長截斷標記）。
12. 暫停期間不送重點更新。

## 依賴
即時重點更新依賴既有 live 雲端摘要。結束附加待辦／章節依賴 #34／#35 已在目前 `main` 的資料；缺資料就省略該段。不依賴未合併的提案。

## 非目標
- 讀取 Slack 頻道、回覆或搜尋 Slack 訊息。
- 自動加入 Google Meet／Zoom，或做 Claude Meetings 那種通話內 bot。
- 主動傳訊給其他使用者的 DM（v1 webhook 也選不了任意人）。若 v2 有人把 webhook 指到自己的 DM，那是使用者自己的設定，App 不另外做「選人傳 DM」UI。
- 自動轉寄到 Email、日曆、Asana／Notion 任務。
- 上傳音訊或照片到 Slack。
- 在 Slack 裡用 @Fireflies 那樣做問答（那會需要讀訊息，屬非目標）。
