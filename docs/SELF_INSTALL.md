# 自裝與真機測試指南（Samsung / 本機 build）

給想今晚就在手機上測 **錄完自動上傳 Google Drive** 的實作清單。更深的 OAuth／行為說明見 [DRIVE_SETUP.md](DRIVE_SETUP.md)。

**這份文件適用分支：** `feature/drive-auto-upload`  
**Repo：** https://github.com/RichardHan/RecorderApp  
**Debug 套件名：** `com.eva.recorderapp.debug`（正式版為 `com.eva.recorderapp`，本指南走 debug）

---

## 0. 你會得到什麼

1. 本機編出 debug APK  
2. 側載到手機（例如 Galaxy S26 Ultra）  
3. 用你的 Google 帳號登入後，**停止並儲存**的錄音會自動進 Drive 資料夾 `RecorderApp Uploads`  
4. **預設可用行動數據**；「Upload over Wi-Fi only」請保持關閉，除非你要測僅 Wi‑Fi

裝別人釋出的 APK 不用設 GCP；**自己 build** 一定要先建 OAuth，否則 Sign in 會失敗或提示 missing client ID。

---

## 1. 前置需求

- [ ] 電腦：macOS / Linux / Windows，能跑 [Android Studio](https://developer.android.com/studio) **或** 已裝好 command-line SDK + JDK 17（專案用 Gradle／Kotlin，建議直接裝 Android Studio）
- [ ] Git
- [ ] USB 線；手機開 **開發人員選項 → USB 偵錯**
- [ ] 要用來登入 Drive 的 **Google 帳號**（稍後要加進 OAuth 測試使用者）
- [ ] 約 30–60 分鐘（多數時間在 GCP 點選）

### 1.1 取得原始碼

```bash
git clone https://github.com/RichardHan/RecorderApp.git
cd RecorderApp
git checkout feature/drive-auto-upload
```

用 Android Studio：**Open** 這個資料夾，等 Gradle sync 完成。第一次 sync 需要網路下載依賴。

---

## 2. 取得 debug SHA-1

GCP 的 **Android OAuth client** 必須綁這支簽章的 SHA-1。本機預設 debug keystore：

```bash
keytool -list -v -alias androiddebugkey \
  -keystore ~/.android/debug.keystore -storepass android
```

- Windows 路徑多半是 `%USERPROFILE%\.android\debug.keystore`
- 若檔案不存在：先用 Android Studio 跑一次任一 debug build，或連過真機／模擬器，系統會自動產生
- 在輸出裡找 **SHA1:**（一串冒號分隔的十六進位），整段複製備用

> 之後若換電腦、刪掉 `debug.keystore`，SHA-1 會變，GCP 要改綁或加新的指紋，否則登入會失敗。

---

## 3. Google Cloud Console（依序做）

到 [Google Cloud Console](https://console.cloud.google.com/)。

### 3.1 專案

- [ ] 新建或選一個專案（名稱任意，例如 `RecorderApp Drive`）

### 3.2 啟用 API

- [ ] **APIs & Services → Library** → 搜尋 **Google Drive API** → **Enable**

### 3.3 OAuth 同意畫面（Consent）

路徑名稱在 Console 可能顯示為 **Google Auth Platform → Branding / Audience** 或舊版 **OAuth consent screen**：

- [ ] User type：**External**
- [ ] 填 App 名稱、使用者支援信箱
- [ ] Publishing status 維持 **Testing**（開發期不要 Publish）
- [ ] **Test users / Audience**：加入你要用來登入的 Gmail  
  **沒加測試使用者 → 真機登入會被拒**

### 3.4 Android OAuth client（給套件名 + SHA-1）

- [ ] **Clients → Create client → Android**
- [ ] Package name：`com.eva.recorderapp.debug`（**含 `.debug`**）
- [ ] SHA-1：貼上第 2 步的值
- [ ] 建立後可記下 Android client ID（Credential Manager **不會**用這個當 Web client ID，但仍需要這筆才能通過 Android 端驗證）

今晚只測 debug 的話，先建這一個即可；release（`com.eva.recorderapp` + release keystore SHA-1）之後再說。

### 3.5 Web application OAuth client（給 Credential Manager）

- [ ] 同一專案再 **Create client → Web application**
- [ ] 名稱任意；重新導向 URI 可先留空／預設
- [ ] 建立後複製 **Client ID**（形如 `xxxx.apps.googleusercontent.com`）

**重點：** App 編譯進 BuildConfig 的是這個 **Web client ID**，不是 Android client ID。詳見 [DRIVE_SETUP.md](DRIVE_SETUP.md)。

---

## 4. 寫入 `local.properties`（不要 commit）

專案**根目錄**的 `local.properties`（Android Studio 通常已自動寫入 `sdk.dir`）：

```properties
sdk.dir=/你的/android/sdk路徑
DRIVE_WEB_CLIENT_ID=你的WebClientID.apps.googleusercontent.com
```

- [ ] 右側貼上 **Web application** 的 Client ID，前後不要空白、不要加引號
- [ ] 確認此檔在 `.gitignore` 內，**絕不要** `git add local.properties`、不要貼到公開地方
- [ ] 未設定時 APK 仍可編過，但 Settings → Cloud 按 Sign in 會提示 **missing client ID**

改完 `DRIVE_WEB_CLIENT_ID` 後請 **重新 assemble**，否則舊 APK 裡還是空字串。

---

## 5. 編 debug APK

在專案根目錄：

```bash
./gradlew :app:assembleDebug
```

成功後 APK 位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

也可用 Android Studio：**Build → Build Bundle(s) / APK(s) → Build APK(s)**，選 debug。

---

## 6. 裝到手機（側載）

### 6.1 用 adb（建議）

1. USB 接上手機，允許「USB 偵錯」授權這台電腦  
2. 確認裝置：

   ```bash
   adb devices
   ```

3. 安裝（覆蓋舊版用 `-r`）：

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### 6.2 複製 APK 手動安裝

把 `app-debug.apk` 傳到手機（雲端硬碟、Nearby Share、USB 檔案傳輸皆可），用檔案總管開啟安裝。

- 若系統擋「未知來源」：依提示允許該檔案管理員／瀏覽器安裝未知 App
- **Samsung：** 部分機型還有「自動攔截程式」或安全資料夾相關提示，依畫面允許這次安裝即可；與 Google 帳號／Play 保護有關的警告可選「仍要安裝」（你自己編的 debug 包）

安裝後桌面／應用程式清單應出現 RecorderApp；套件為 debug variant。

---

## 7. 第一次在 App 裡測上傳

1. 打開 App，依系統提示給 **麥克風**、**通知**（背景錄音會用到）等權限  
2. 進 **Settings（設定）→ Cloud**  
3. 點 **Sign in with Google**，選**已加進 Test users**的帳號，允許 Drive 相關權限（scope 為 `drive.file`，只碰 App 自己建立的檔／資料夾）  
4. 確認畫面有顯示你的 email  
5. 開啟 **Auto-upload recordings**  
6. **Upload over Wi-Fi only 保持關閉**（要用行動數據上傳）  
7. 回到錄音頁，錄一小段 → 點 **停止並儲存**（**不要 Cancel**；取消不會上傳）  
8. 等數秒～數分鐘（WorkManager 排程，不是按下就瞬間完成）  
9. 用瀏覽器或 Drive App 打開 Google Drive，找資料夾 **`RecorderApp Uploads`**，應出現剛存的檔案

建議再測：開關飛航 → 連回網路後，待傳佇列應會重試（行為說明見 DRIVE_SETUP）。

---

## 8. 疑難排解

| 狀況 | 可能原因 | 怎麼辦 |
| --- | --- | --- |
| Sign in 提示 missing client ID | `local.properties` 沒設或設完沒重編 | 檢查 `DRIVE_WEB_CLIENT_ID`，再跑 `./gradlew :app:assembleDebug` 重裝 |
| 登入彈窗後失敗／無法選帳號 | SHA-1 或 package 與 GCP Android client 不符 | 確認 package 是 `com.eva.recorderapp.debug`；用**這台電腦**的 debug SHA-1 更新 GCP |
| 「App 尚未完成驗證」或無法繼續 | 帳號不在 Test users，或同意畫面不是 Testing | 把該 Gmail 加進測試使用者；等幾分鐘再試 |
| 登入成功但不上傳 | 沒開 Auto-upload；按了 Cancel；沒網路 | 開開關；務必 Stop/Save；確認有行動數據或 Wi‑Fi |
| 一直卡在等待 | 開了 Wi‑Fi only 但沒 Wi‑Fi；系統限制背景 | 關閉 Wi‑Fi only；看下一列電池優化 |
| 背景很久才傳／從不傳 | 省電／電池優化殺掉 Worker | 設定裡對 RecorderApp：**不優化電池**／允許背景活動（Samsung 常見在「電池與裝置維護」或 App 資訊 → 電池） |
| Drive 裡沒有資料夾 | 尚未成功上傳過；或看錯帳號 | 用**同一個**登入帳號開 Drive；成功上傳後會建立 `RecorderApp Uploads` |
| 舊錄音沒上雲 | 本版不做舊檔批次補傳 | 只影響**開啟 auto-upload 之後**新完成的錄音 |

更深的實作細節、Room 狀態、重試與「本版不做的事」：見 [DRIVE_SETUP.md](DRIVE_SETUP.md)。

---

## 9. 超短對照（可邊做邊勾）

1. `git clone` → `checkout feature/drive-auto-upload`  
2. `keytool` 拿 debug SHA-1  
3. GCP：專案 → 開 Drive API → Consent Testing + 測試使用者  
4. Android client（`com.eva.recorderapp.debug` + SHA-1）+ **Web** client  
5. 根目錄 `local.properties` 寫 `DRIVE_WEB_CLIENT_ID=...`（勿 commit）  
6. `./gradlew :app:assembleDebug` → `adb install -r app/build/outputs/apk/debug/app-debug.apk`  
7. Settings → Cloud → Sign in → 開 Auto-upload → Wi‑Fi only **關** → 錄音並儲存 → 查 Drive `RecorderApp Uploads`

---

## 相關文件

- [DRIVE_SETUP.md](DRIVE_SETUP.md) — OAuth／上傳行為／英文摘要  
- 上游專案：https://github.com/tuuhin/RecorderApp（本 fork 的錄音本體來源）
