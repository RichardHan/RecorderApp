# 手動建置 APK（GitHub Actions）

工作流程檔：`.github/workflows/manual_apk_build.yml`。只能手動觸發（`workflow_dispatch`），push 或 PR 都不會自動建置，所以不會自動消耗 Actions 分鐘數。

## 產出
- arm64、native 函式庫壓縮的 debug APK，指令跟本機打包一樣：`./gradlew :app:assembleDebug -Parm64Only=true -PcompressNative=true`。
- 會檢查 zipalign（`-P 16 4`）、apksigner、APK 是否小於 100 MB。
- 上傳的 artifact 包含 APK 和 `SHA256SUMS.txt`，保留 14 天；Run 摘要頁會列出檔名、大小、sha256 和簽章憑證。
- 環境：JDK 17（temurin）、platform `android-37.0`、build-tools `37.0.0`、NDK `30.0.16248370`。版本跟 `gradle/libs.versions.toml` 及本機建置一致。
- 所有 action 都固定在 commit SHA；`permissions: contents: read`；checkout 不保留 GitHub 憑證；會驗證 Gradle wrapper。

## 讓手機能直接更新安裝（需要使用者決定一次）
目前手機上的 App 是用本機的 debug keystore 簽章的，憑證 SHA-256 為 `7bfd64ee…4d36`。
GitHub runner 上每次都會產生一把新的 debug key，用它簽出來的 APK **沒辦法覆蓋安裝，必須先解除安裝（App 資料會一起被刪除）**。

如果要讓 CI 產出的 APK 能直接更新，需要使用者**明確同意**把這把 debug keystore 放進 repo 的 Actions secret：
- Secret 名稱：`DEBUG_KEYSTORE_B64`，內容是 `debug.keystore` 檔案的 base64 編碼。
- 設定方式：GitHub repo → Settings → Secrets and variables → Actions → New repository secret。
- 安全性：
  - 工作流程只會把這個 secret 寫到 runner 上的 `~/.android/debug.keystore`，結束後就刪除，不會印出內容。
  - 建置完成後會比對 APK 的憑證；跟 `7bfd64ee…4d36` 不一致就讓建置失敗。
  - Secret 不會進入快取或 artifact。

如果沒有設定這個 secret，建置預設會**直接失敗**，不會產出無法更新的 APK。

### Google Drive 登入用的 client ID
- Secret 名稱：`DRIVE_WEB_CLIENT_ID`，內容是 Web application OAuth client ID（不是密碼或 token，見 `docs/DRIVE_SETUP.md`）。
- 建置前會檢查它有沒有設定，沒設定就直接失敗；建置後再確認 APK 裡真的有這個值。兩個步驟都只回報「有／沒有」，不會印出內容。
- 少了它，APK 雖然裝得起來，但 Settings → Cloud 登入會跳出「DRIVE_WEB_CLIENT_ID is missing」。
如果只是想測試 CI 能不能跑，可以在觸發時勾選 `allow_ephemeral_signing`。這時檔名會標示 `ephemeral`，這個 APK 不能覆蓋安裝現有的 App。

## 啟用方式（重要）
GitHub 只會在 Actions 頁面顯示**預設分支（目前是 `main`）上存在的** `workflow_dispatch` 工作流程。
- 這個 PR 的目標分支是 `feature/drive-auto-upload`。合併進去之後，還要讓這個檔案出現在 `main` 上，例如把 feature 分支合併到 `main`，或只把這個工作流程檔另外合併到 `main`。之後才能在 Actions → Manual APK build → Run workflow 選分支（例如 `feature/drive-auto-upload`）來建置。
- 或者也可以在 repo 設定裡，把預設分支改成 `feature/drive-auto-upload`。這會影響 clone 和 PR 的預設分支，請自行決定。
- 啟用後，可以用 `gh workflow run "Manual APK build" --ref feature/drive-auto-upload` 觸發，再用 `gh run download` 下載 APK。

## 跟既有 CI 的關係
既有的 `android_build_ci.yaml` 會在 `main` 的 push 和 PR 時自動建置一般的 debug APK，這次沒有修改。新的工作流程用途不同：只能手動觸發、只建 arm64、會檢查簽章憑證是否一致，並上傳 APK。

## Gradle 發行版下載（暫時性 504 處理）

官方 wrapper 網址 `https://services.gradle.org/distributions/gradle-9.8.0-bin.zip` 會轉址到 GitHub release asset，偶爾回 HTTP 504 或逾時。
workflow 在 build 前先執行「Pre-fetch Gradle wrapper distribution」步驟：

- 只使用 `gradle-wrapper.properties` 裡的官方網址（不同即失敗），不使用鏡像，也不更換版本。
- 最多下載 6 次（間隔 30/60/90/120/150 秒，每次上限 600 秒），整步約 15–25 分鐘後仍失敗就 fail closed。
- 下載後以官方公布的 SHA-256（`bafd5ce9…58e6c`，來源 `gradle-9.8.0-bin.zip.sha256`）驗證，再放到 wrapper 的 dists 目錄，`./gradlew` 不會再下載一次。
- 若 setup-gradle 快取已有安裝好的發行版（`.ok` 標記存在）就直接略過。

這只降低暫時性網路錯誤的影響；官方來源長時間無法連線時，build 仍會失敗。

## Debug keystore 路徑

AGP 會優先從 `ANDROID_USER_HOME` 找 debug keystore。restore 步驟會把 `ANDROID_USER_HOME` 設成 `$HOME/.android`，並在 build 前用 keytool 比對憑證 SHA-256（只印公開指紋）；不符就直接失敗，不會產出用別把 key 簽的 APK。

## sherpa-onnx AAR 下載

`settings.gradle.kts` 會從官方 GitHub release 下載 sherpa-onnx AAR，只試一次。workflow 會先用同一個網址做相同的有限次重試（6 次），以 `settings.gradle.kts` 裡固定的 SHA-256 驗證，再透過文件化的 `SHERPA_ONNX_AAR` 環境變數交給 Gradle（Gradle 會再驗一次 hash）。

## Gradle 依賴下載重試

build 指令帶 `org.gradle.internal.repository.max.tentatives=6`、`initial.backoff=5000`，以及 HTTP 連線/讀取逾時（60s/120s）。這些是 Gradle 的內部屬性（可用但官方不保證支援），只在 CI 指令列設定，不影響本機 build。

## Gradle 快取（feature 分支可寫入）

setup-gradle 預設只在預設分支（main）寫快取，所以 `feature/drive-auto-upload` 上每次成功下載的依賴都會被丟掉，下次 run 又要重下載。現在只有「本 repo 的 workflow_dispatch、分支是 main 或 feature/drive-auto-upload」時才允許寫入（`cache-read-only` 條件式；觸發 dispatch 需要 repo 寫入權限，這個 workflow 也沒有 fork／PR 觸發）。
依 setup-gradle 文件，快取在 job 結束時儲存，build 失敗時也會存（cache cleanup 預設只在成功時執行），所以已下載的部分會留給下一次 run。
另外把通過 SHA-256 驗證的官方 sherpa-onnx AAR 放在 `$GRADLE_USER_HOME/sherpa-onnx-aar` 一起快取。keystore 放在 `~/.android`，不在 Gradle User Home 裡，不會進快取或 artifact。
