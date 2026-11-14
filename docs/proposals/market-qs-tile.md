# 提案：快速設定 Tile，從狀態列下拉直接開始／暫停錄音

> 狀態：**規格提案，尚未實作，等待使用者選擇 agent；勿合併視為功能完成。**
> 這個 PR 只有規格文件，沒有程式碼、設定或 workflow 變更。
> 基準：`main` @ 3903947。來源存取日期：2026-11-14（台北時間）。

這不是某家會議筆記服務的功能搬運，而是官方 Android 系統能力：讓錄音 App 在「快速設定」提供一顆 Tile。本 App 已有主畫面 Widget 與錄音通知控制，但程式庫中沒有 `TileService`。從下拉面板一鍵開始／暫停，對「拿出手機就要錄」很實用。

## 參考來源（公開官方說明）

### Android Quick Settings tiles
- Create custom Quick Settings tiles：https://developer.android.com/develop/ui/views/quicksettings-tiles （本頁於 2026-11-14（台北時間） 開啟）
  - Quick Settings 是狀態列下拉面板中的 Tile，讓使用者不打開 App 就能做關鍵動作。
  - 實作 `TileService`；使用者需先把 Tile 加進快速設定。可用 `Tile` 狀態（例如 active／inactive）反映開關狀態。
  - 官方提醒應只把真正常用的控制做成 Tile，並正確更新狀態與內容說明。
- `android.service.quicksettings` API：https://developer.android.com/reference/android/service/quicksettings/package-summary （本頁於 2026-11-14（台北時間） 開啟）
  - API 24 起提供。
- `Tile`：https://developer.android.com/reference/kotlin/android/service/quicksettings/Tile （本頁於 2026-11-14（台北時間） 開啟）
  - 以 `state`、`label`、`subtitle`、`contentDescription` 等向系統回報目前狀態。

**獨立設計（非複製其他產品 UI）**：一顆「錄音」Tile，狀態與現有前景錄音服務同步；點擊行為對齊 Widget 與通知上既有的開始／暫停／繼續，不另闢一套錄音引擎。

## 與 RecorderApp 現況的關係
- 已有：`feature/widget` Glance 錄音／錄音列表 Widget、錄音通知（暫停等）、播放器 `MediaSession`（鎖屏／通知控制播放，不是錄音）。
- 在 `origin/main` @ 3903947 搜尋：沒有 `TileService`、沒有 Quick Settings 相關實作、沒有 Wear OS 模組。
- 本提案不做 Wear OS、不改 Widget、不重做鎖屏播放控制。

## 使用者問題
要開始錄音時，還得先打開 App 或回到主畫面找 Widget。快速設定一直在狀態列下拉裡，比 Widget 更容易碰到。

## 最小範圍（v1）
- 新增一個 `TileService`（一顆 Tile 即可）：
  - 未在錄音：`STATE_INACTIVE`，標籤如「開始錄音」。點擊後啟動既有錄音流程（同一支 recorder service）。
  - 錄音中：`STATE_ACTIVE`，標籤如「錄音中／點一下暫停」。點擊暫停。
  - 已暫停：清楚的第三態（若系統 state 不夠表達，用 subtitle／label 區分，例如「已暫停／點一下繼續」）。點擊繼續。
- **不要**在同一顆 Tile 上用「長按停止」當唯一停止方式（發現性差）。v1 建議：點擊只切換開始／暫停／繼續；停止仍走通知或 App（避免下拉時誤觸直接結束並存檔／丟棄）。若實作要加停止，必須是另一次明確確認（例如點擊後跳出系統面板確認），不得單次誤點就結束。
- 權限：尚未授予麥克風時，點擊不得假裝開始錄音；應打開 App 權限說明或系統權限請求（依官方 Tile 與前景服務限制，能在 Tile 直接請求就直接請求，否則 `startActivityAndCollapse` 開既有權限畫面）。
- 若現有流程在開始前有品質設定或儲存位置檢查失敗，Tile 需把使用者帶回 App 並顯示同一錯誤，不得靜默失敗。
- 狀態必須與 service 雙向同步：從 App、通知或 Widget 開始／暫停，Tile 圖示與標籤在下一次可見或透過 `requestListeningState`／適當更新時立刻正確。不允許 Tile 顯示「未錄音」但服務已在錄。
- 宣告於 manifest，使用者可自行加入快速設定。不在安裝後強迫釘選。
- 與現有 `foregroundServiceType="microphone"` 錄音服務共用，不開第二條錄音管線。

## UI
- 圖示需能在淺色／深色快捷面板辨識；提供 `contentDescription`（例如「開始錄音」「暫停錄音」）。
- 不加入廣告、設定捷徑或與錄音無關的第二顆 Tile（v1）。
- 開始成功後，既有錄音通知必須出現（與從 App 開始相同），讓使用者知道正在錄、也能從通知暫停。

## 資料模型
- 無新資料表。Tile 只讀既有錄音 session 狀態。
- 不另存「從 Tile 開始」的特殊錄音類型。

## API／成本／隱私
- 無雲端 API、無外送。點擊等於使用者主動開始麥克風錄音，與按 App 內錄音鈕相同，適用既有的錄音告知／通知行為。
- 不得因加了 Tile 就在背景常駐聽筒（不是 always-on mic）。`TileService` 只在系統綁定／點擊時運行。

## 取消、競態、離線
- 連點：必須 debounce 或忽略進行中的 start／pause，避免雙重 start 開兩條 session。
- 通話中若現有設定為「來電時暫停錄音」（`pauseRecordingOnCall`），Tile 行為必須遵守同一規則，不可從 Tile 繞過。
- 完全離線可用。儲存空間不足等失敗路徑與 App 內開始相同。

## 驗收測試
- 未授權麥克風：點 Tile 不會開始寫檔；會引導授權。
- 授權後點 Tile：開始錄音，通知出現，Tile 變為錄音中；再點則暫停；再點則繼續。音檔為同一則，不是兩則新錄音。
- 在 App 內開始錄音後下拉：Tile 已是錄音中。從通知暫停後，Tile 顯示已暫停。
- 停止（從通知或 App）後，Tile 回到「開始錄音」。
- 快速連點不會建立兩則並行錄音。
- 與 Widget 同時使用：兩邊狀態一致。
- 旋轉／行程被系統回收後，下一次 listening 狀態正確（不殘留 ACTIVE）。

## 依賴
- 既有 recorder service、通知、權限流程、Widget 所用的狀態來源（應共用同一個 repository／service 狀態，不要各讀各的旗標）。
- 不依賴任何未合併提案。

## 非目標
- Wear OS 錶面按鈕或 Watch 錄音 App。
- 新的鎖屏錄音控件（錄音中已有通知；鎖屏播放是 MediaSession，保持現狀）。
- 自動開始錄音、排程錄音、或偵測會議軟體就開麥。
- 多顆 Tile（選擇音源、雲端開關等）留待有明確需求再議。
