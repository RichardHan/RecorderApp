# 手動建置 APK（GitHub Actions）

工作流程檔：`.github/workflows/manual_apk_build.yml`。只能手動觸發（`workflow_dispatch`），push 或 PR 都不會自動建置，也不會發佈 GitHub Release，只上傳 artifact。

## 產出
- 建置 repo 中唯一的 App（`:studio`，OpenRouter Recorder，`io.github.richardhan.voicenote`）：arm64、native 函式庫壓縮的 debug APK，指令跟本機一樣：`./gradlew assembleDebug -Parm64Only=true -PcompressNative=true`。
- 會檢查 zipalign（`-P 16 4`）、apksigner、APK 是否小於 100 MB。
- 上傳的 artifact（`openrouter-recorder-debug-arm64`）包含 `OpenRouterRecorder-debug-<版本>-arm64-<stable|ephemeral>-<sha>.apk` 和 `SHA256SUMS.txt`，保留 14 天；Run 摘要頁會列出檔名、大小、sha256 和簽章憑證。
- 環境：JDK 17（temurin）、platform `android-37.0`、build-tools `37.0.0`。
- 所有 action 都固定在 commit SHA；`permissions: contents: read`；checkout 不保留 GitHub 憑證；會驗證 Gradle wrapper。

## 簽章（固定 debug keystore）
- Secret 名稱：`DEBUG_KEYSTORE_B64`，內容是 `debug.keystore` 的 base64。
- 工作流程只把它寫到 runner 上的 `~/.android/debug.keystore`，結束後刪除，不印出內容，也不進快取或 artifact。
- build 前後都會比對憑證 SHA-256（`7bfd64ee…4d36`），不一致就失敗。
- 沒有設定這個 secret 時預設直接失敗；只想測 CI 可勾選 `allow_ephemeral_signing`，檔名會標示 `ephemeral`，這種 APK 不能覆蓋安裝。

## 觸發與下載
`gh workflow run "Manual APK build" --ref main`，完成後用 `gh run download` 下載。

## 網路重試
- Gradle 發行版：先以 `gradle-wrapper.properties` 的官方網址有限次重試下載，並以官方 SHA-256 驗證。
- 依賴下載：build 指令帶 Gradle 內部的重試／逾時屬性（只在 CI 指令列設定）。
- sherpa-onnx AAR 由 Gradle 解析，SHA-256 固定在 `gradle/verification-metadata.xml`。

## Gradle 快取
setup-gradle 只在「本 repo 的 workflow_dispatch、分支是 main」時寫入快取，其餘情況唯讀。
