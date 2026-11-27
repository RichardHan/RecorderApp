# OpenRouter Recorder

一支從頭重寫（clean-room）的 Android 錄音 App，原始碼在 [`studio/`](studio/)，Gradle 專案是 `:studio`，
Application ID 為 `io.github.richardhan.voicenote`。這個 repo 現在只保留這一支 App；原本從上游 fork 下來的
舊版 `:app`（`com.eva.recorderapp`）及其 `core/`、`data/`、`feature/` 等模組已移除，需要時可在 git 歷史中找到。

## 功能

- 背景錄音支援 AAC、Opus、AMR-NB、AMR-WB 和可調位元率。可從通知暫停、繼續及儲存。可選擇來電暫停，以及加入裝置最近取得的位置。
- 播放支援媒體通知、定位與速度控制。可為錄音命名、分類、加到最愛、寫筆記、分享，或移到垃圾桶後還原。錄音和播放時皆可加入書籤並匯出 CSV。
- 下載 SenseVoice 模型後，可在裝置上轉錄已儲存的音檔。逐字稿可編輯並匯出 TXT、Markdown；有分段時間的稿件也可匯出 SRT 與 WebVTT。
- 透過 OpenRouter 或自訂 HTTPS 端點做雲端轉錄，送出前會先確認，並顯示模型、耗時與服務回報費用。
- 匯入音檔與 YouTube 連結（也可從其他 App 分享 `audio/*` 或連結進來）。
- AAC／M4A 剪輯（預覽後再儲存）。
- 本機離線摘要（LiteRT-LM，模型下載到 App 私有空間）。
- 離線全文搜尋已存的逐字稿（中英日，不呼叫任何 API）。
- 離線發言者時間軸（sherpa-onnx），可命名發言者、篩選並匯出 UTF-8 文字。
- 介面支援英文、繁體中文、簡體中文、日文，並有深色模式。

可從桌面小工具或捷徑開啟錄音與最近檔案。「問這段錄音」支援在確認後傳送逐字稿與最近問答給自選的對話模型，並可查看回答引用的原文段落。使用者可選擇用 Android Keystore 儲存連線。

## 從舊版匯入錄音

OpenRouter Recorder 會與舊版 RecorderApp 並存。要搬移錄音，可在舊版分享音檔給 OpenRouter Recorder，再按「匯入」；也可以在新 App 按「選取音檔」，一次選取多個檔案。支援舊版錄製的 M4A、Ogg Opus、AMR 窄頻與寬頻音檔。匯入會複製音訊，原始檔仍在原處。舊版的逐字稿、聊天紀錄與設定需在舊版查看。

## 建置

需求：JDK 17、Android SDK（platform `android-37.0`、build-tools `37.0.0`）。在 repo 根目錄執行：

```sh
./gradlew test lintDebug assembleDebug
```

Debug APK 輸出在 `studio/build/outputs/apk/debug/studio-debug.apk`。只要 arm64 且壓縮 native 函式庫的側載版：

```sh
./gradlew assembleDebug -Parm64Only=true -PcompressNative=true
```

sherpa-onnx AAR 由 Gradle 從官方 GitHub release 下載，SHA-256 固定在 `gradle/verification-metadata.xml`。

## CI（`.github/workflows/`）

| Workflow | 觸發 | 內容 |
|---|---|---|
| `android_build_ci.yaml` | push／PR 到 `main` | `lintDebug assembleDebug bundleRelease`，上傳 debug APK 預覽與 lint 報告 |
| `unit_tests.yml` | push／PR 到 `main` | `./gradlew test` |
| `instrumented_tests.yml` | push／PR 到 `main`、手動 | API 36 模擬器跑 `connectedDebugAndroidTest` |
| `pr-apk.yml` | push 到非 `main` 分支 | 單元測試 + arm64 debug APK artifact |
| `manual_apk_build.yml` | 只能手動 | 固定簽章的 arm64 debug APK，只上傳 artifact（不發佈 release），見 [docs/ci/manual-apk-build.md](docs/ci/manual-apk-build.md) |

## 授權

見 [LICENCE](LICENCE)；`studio/src/main/assets/licenses/` 內為第三方模型與函式庫的授權聲明。
