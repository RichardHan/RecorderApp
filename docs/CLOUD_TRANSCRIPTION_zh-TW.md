# 雲端重轉、比較與摘要（使用說明）

## 設定（只需一次）
1. 開啟 App →「設定」→「AI」分頁 → 展開「連線」。
2. 介面格式：
   - **OpenAI 相容服務**：Endpoint 填到 `/chat/completions` 前面，例如 `https://api.example.com/v1`（範例網址，請替換為你的服務網址）。App 會呼叫 `POST {Endpoint}/chat/completions`。
   - **Gemini 原生**：Endpoint 例如 `https://generativelanguage.googleapis.com/v1beta`，呼叫 `models/{模型}:generateContent`。
3. 在「OpenAI 相容服務」下點選轉錄模式：品質優先使用 `gemini-4-hydrogen`（預設），另有 Gemini 3.8 Flash 或 Gemini 3.5 Transcribe（區分說話者）。升級時，舊的 Argon 品質預設會切換到 Hydrogen；其他模型與摘要設定保留。服務端須支援該模型 ID；若要繼續用 Argon，可手動輸入 `gemini-4-argon` 後儲存。摘要預設仍是 Gemini 3.8 Flash。
4. 貼上 API Key →「儲存 Key」。Key 以 Android Keystore 加密保存，可在設定畫面查看完整內容並選取複製；不會寫入記錄檔、備份或 APK，可隨時「移除 Key」。
5. 按「儲存設定」。

## 手動雲端重轉
播放畫面右上「⋮」→「逐字稿／雲端重轉／摘要」→「雲端重新轉錄」→ 確認上傳。
- 錄音在手機上轉成 MP3（16 kHz、單聲道、64 kbps），預設每 300 秒一段、同時 2 段（可設 1–6）。
- OpenAI 相容轉錄每段以 `input_audio`（base64、format=mp3）送出，`max_tokens=16384`、`stream=false`、逾時 120 秒；`reasoning_effort` 預設 low，Hydrogen 轉錄及模型名稱含 `transcribe` 或 `flash-lite` 時不送。
- 失敗只重試**同一服務**最多 3 次，不會改用別家。可隨時「取消」；已完成段落有斷點，按「重試未完成段」只重送失敗段。
- 原始錄音與離線逐字稿不會被修改，每次雲端重轉另存一份，並記錄服務主機、模型、effort、分段設定。

## 和原本桌面流程不同的安全修正
- 網路錯誤、模型空白回應**不會**被當成靜音，會在稿中標示「⚠ 轉錄失敗／空白回應，可重試」。
- 不以檔案大小（<500 KB）判斷靜音。「略過近乎無聲的段落」預設關閉（避免小聲說話被當成靜音）；開啟後才會在整段每 100 ms 音量都極低時標為「靜音」並跳過上傳。
- 內容過濾：該段最多切 3 小段（每段 ≥ 60 秒），每小段只送一次；仍被過濾的部分在稿中標示時間範圍，不會默默省略。每次轉錄有請求總數上限以避免額外計費。
- 幻覺警示（某段長度 > 中位數 2.5 倍）可在設定關閉。

## 自動轉錄（預設關閉）
設定中開啟「錄音完成後自動雲端轉錄」需再次確認。只對成功儲存的錄音觸發，取消的錄音不會上傳；可限制只在 Wi‑Fi。

## 比較與摘要
在同一畫面勾選兩份逐字稿的「比較」即可並排檢視。按「用此稿摘要」會用設定的摘要模型產生繁中摘要，另存為獨立結果，不會覆蓋逐字稿。

## 費用與隱私
音訊與逐字稿會送到你設定的服務；行動網路可能產生流量費，API 依服務計費。App 內沒有內建任何 Key 或 Endpoint。

## 技術備註
- MP3 編碼使用 jump3r 1.0.5（LAME 的純 Java 移植，LGPL‑2.1+，約 350 KB，不需 NDK／ffmpeg）。
- 結果存在 App 私有目錄 `files/ai_transcripts/v1/<錄音ID>/`（transcripts / summaries / checkpoints），離線與雲端共用同一格式，方便日後加入更多離線模型。
