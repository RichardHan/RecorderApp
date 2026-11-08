# Google Drive 自動上傳設定 / Google Drive upload setup

## 目前實作 / Current implementation

- `applicationId` 未改變：正式版是 `com.eva.recorderapp`，debug 是 `com.eva.recorderapp.debug`。
- 使用使用者本人的 Google 帳號（Credential Manager + AuthorizationClient），**不是 service account**。OAuth scope 僅 `drive.file`；App 只能存取自己建立的資料夾及檔案，不會讀取整個 Drive。
- 設定 → Cloud 登入 Google，允許 Drive 權限，再開啟 Auto-upload recordings。
- 錄音成功存檔後用 WorkManager 排程；取消錄音不會上傳。預設建立 `RecorderApp Uploads` 資料夾。
- **預設允許行動網路（NetworkType.CONNECTED），「Upload over Wi-Fi only」選項預設關閉。** 開啟後才限制 UNMETERED（通常為 Wi-Fi）。
- 使用 resumable upload、指數退讓與同一錄音查重；Room v10 儲存 PENDING/RUNNING/SUCCESS/FAILED 狀態。Access token 不寫進資料庫或 DataStore，不在 UI 顯示。
- 本版不包含自訂 Drive 資料夾選擇器，選定資料夾後會存在 DataStore；待重授權時要回到 Cloud 設定頁操作。

## Google Cloud 設定（你要做）

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 建立／選擇專案，啟用 **Google Drive API**。
2. 在 **Google Auth Platform → Branding / OAuth consent screen** 填 App 名稱、聯絡信箱；開發期間選 **External → Testing**，到 **Audience / Test users** 加你的 Gmail。
3. 到 **Clients → Create client → Android** 建 Android OAuth client：
   - debug APK：package name `com.eva.recorderapp.debug`。
   - release APK：package name `com.eva.recorderapp`。
   - 各自填實際簽章金鑰的 SHA-1。沒有 release keystore 前，先只建 debug client 即可。
4. 同一專案再建 **Web application** OAuth client。**Credential Manager 使用的是這個 Web client ID**，不是把 Android client ID 當 Web client ID。此處不需要 service account、client secret 或後端伺服器。
5. 取得 SHA-1（在專案根目錄執行；debug keystore 預設密碼為 `android`）：

   ```bash
   keytool -list -v -alias androiddebugkey \
     -keystore ~/.android/debug.keystore -storepass android
   ```

6. 在**沒有進版控的** `local.properties` 加 Web client ID（替換右側，不含空白）：

   ```properties
   sdk.dir=/your/android/sdk
   DRIVE_WEB_CLIENT_ID=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
   ```

   CI 也可設定同名環境變數。未設定時會建置成功，但按 Sign in 會提示 missing client ID，不會試圖送出假憑證。**不要提交憑證 JSON、access token、keystore 或密碼。** Android client ID 雖非 secret，仍沿用 local.properties／BuildConfig，方便各環境使用不同的專案。
7. `./gradlew :app:assembleDebug`，安裝該 debug variant。套件名或 SHA-1 任一想換，都需同步調整 GCP Android client，並重新安裝對應 APK。

## 真機驗證

1. 設定 → Cloud，登入允許使用的測試帳號並授權 Drive；確認畫面顯示 email。
2. 開 Auto-upload recordings，Wi-Fi-only 保持 OFF；關掉 Wi-Fi，留行動網路。
3. 錄一小段並「停止、儲存」（不是 Cancel），稍等排程與上傳；Drive 的 `RecorderApp Uploads` 應出現檔案。錄音多長或後台限制會影響完成時間，不是即時 HTTP response。
4. 飛航模式再錄一段：等待連線後自動重試。接著開 Wi-Fi-only 確認行動網路不會傳、接上 Wi-Fi 才傳。
5. 錄音若被刪除、Drive 授權撤銷、配額不足或超過重試次數，Worker 會失敗；Room 可查 `google_drive_uploads`。本第一版沒有手動重新佇列 UI；重開 auto-upload 只影響之後完成的錄音，尚未做舊檔批次補傳。
6. 真機請同時檢查 OAuth、Drive file ID／檔名格式與 Android 背景 WorkManager。此分支在沒有實際 GCP client／裝置前，不能宣稱 E2E 上傳已驗證。

## English brief

Use **user OAuth**, never a service account. Enable Drive API, configure the External/Testing consent screen and add test users. Create Android OAuth clients for `com.eva.recorderapp.debug` (debug SHA-1) and `com.eva.recorderapp` (release SHA-1). Also create a **Web application client**; put its client ID—not a secret or access token—in the gitignored root `local.properties` as `DRIVE_WEB_CLIENT_ID`. CI can use the same environment variable. Build the matching variant, go to **Settings → Cloud**, sign in, grant `drive.file`, and enable automatic uploads.

Completed recordings upload into **RecorderApp Uploads**. Any connected network is allowed by default; the optional Wi-Fi-only switch is off and switches to UNMETERED only when enabled. WorkManager handles network constraints and exponential retries. The uploader uses resumable uploads, a pre-generated Drive file ID, and recording metadata to avoid duplicates. OAuth tokens are not persisted. Room v10 records local upload state. Custom folder selection, bulk backfill of old recordings, and a manual retry control are intentionally deferred.
