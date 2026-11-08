# 離線語音模型（下載、切換、離線重轉）

## 可下載的模型（設定 › 轉錄）
| 模型 | 下載 | 解壓後 | 授權 | 說明 |
|---|---:|---:|---|---|
| SenseVoiceSmall int8 | 163 MB | 約 229 MB | FunASR Model License | 最快、最省資源 |
| Qwen3-ASR 0.6B int8 | 879 MB | 約 937 MB | Apache-2.0 | 較準、較慢 |
| Qwen3-ASR 1.7B int8（實驗性） | 2,404,222,421 bytes（約 2.40 GB，六個檔案逐一下載） | 同左（不壓縮） | Apache-2.0 | 最準、最慢、最吃記憶體 |
| FireRedASR2-AED int8（完整 AED，非 CTC） | 839 MB | 約 1.18 GB | Apache-2.0 | 最耗記憶體 |
| Vosk 小模型（原有） | 37–46 MB | | Apache-2.0 | 串流即時出字 |

- 下載來源固定為 sherpa-onnx 官方 GitHub release，先驗證整包 SHA-256，再只解出清單內的檔案並逐檔驗證大小與 SHA-256（測試音檔等不解出）；拒絕路徑跳脫與符號連結。
- 下載前檢查剩餘空間；完成前先放在暫存資料夾，成功才切換，失敗或取消會清掉暫存且保留舊版本。

## Qwen3-ASR 1.7B int8（實驗性）

**來源（社群轉檔，非 k2-fsa 官方發佈）**：Hugging Face `thieunv-asilla/sherpa-onnx-qwen3-asr-1.7B-int8`，固定 revision
`69eb686fd94a4a865bb5340a3d6ac0d7f1fec0d5`（2026-07-23）。原模型 `Qwen/Qwen3-ASR-1.7B`，Apache-2.0；以
Wasser1462/Qwen3-ASR-onnx 匯出（`--max-total-len 512`，INT8 動態量化）。App 不重新散布權重，只從上述 revision 下載。

| 檔案 | bytes | SHA-256 |
|---|---:|---|
| conv_frontend.onnx | 48,080,441 | 3cb27a9fe94d95c938e476f2012b21aba2ec0bfceef33b0e58acd208946bafdd |
| encoder.int8.onnx | 314,222,162 | a5deedae034ece715de8ed204378d8c77f889af3a60c2566581135e84cced7cd |
| decoder.int8.onnx | 2,037,458,645 | c43c853fa6e97d08365cb8a5502b360b595cd43c00dc60e4d8ca7cc18cad460b |
| tokenizer/vocab.json | 2,776,833 | ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910 |
| tokenizer/merges.txt | 1,671,853 | 8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5 |
| tokenizer/tokenizer_config.json | 12,487 | 4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c |
| **合計** | **2,404,222,421** | |

**下載**：按下載鍵會先顯示大小；預設「僅 Wi‑Fi」，要用行動數據必須另外按「允許行動數據」。每個檔案串流寫入
`<id>.partial/…part`，支援 HTTP Range 續傳；可按「取消」，已下載部分保留，下次續傳。每個檔案大小與 SHA-256
都對過才改名，全部通過後整個資料夾一次搬進安裝位置（原子安裝）；SHA 不符的檔案會刪掉重下。開始前要求
可用空間 ≥ 尚需下載量 + 3.5 GB（全新下載約 6 GB）。

**記憶體（RAM）**：Linux x64、sherpa-onnx 2.0.1、App 相同設定（2 執行緒）實測峰值 RSS 約 3.3 GB（載入後）～3.7 GB（20 秒片段）。
載入前 App 會讀取手機**實際可用記憶體**，需 ≥ 模型檔大小 ×1.3 + 512 MB（約 3.6 GB），且只支援 64 位元；不足時只停用逐字稿，錄音照常保存。
同一時間只載入一個離線模型（不會和 0.6B 或其他模型同時常駐）。不會自動下載，也不會自動載入；需使用者自己選它並開啟逐字稿。

**解碼設定**：與 0.6B 相同（sherpa-onnx v2.0.1，featureDim 128，maxTotalLen 512——匯出時的上限，maxNewTokens 192），
分句最長 20 秒。若某句輸出達 192 token，文字後會標「［…可能截斷］」，不會無聲截掉。模型輸出前綴 `language Chinese<asr_text>` 會被去除。

**Linux 冒煙測試（不是手機實測）**：`SherpaJniSmokeTest.qwen3_1_7b`，載入 8.4 s；ASCEND 中英混合片段：
4.0 s 音訊→解碼 2.6 s「那我专业是那个ISM，Information Systems Management。」；1.6 s→1.4 s「哦，这么technical的。」；
1.8 s→2.0 s「所以，我上学期也学了Python。」；20 s 合成片段→10.4 s、58 tokens，無截斷。

**尚未驗證**：Galaxy S26 Ultra 上的實際速度、記憶體、發熱；本版只在 Linux x64 驗證過模型與設定。

## 即時轉錄（錄音時）
- 在「轉錄」開啟並選擇模型後才會載入模型；關閉時不佔記憶體。
- sherpa 模型屬「分句辨識」：偵測到停頓（約 0.6 秒）才把整句送去辨識，所以字會在停頓後出現；單句最長 20 秒。
- 錄音本身完全不受影響。若手機辨識速度跟不上，該段會在逐字稿中明確標示「辨識跟不上，此段未辨識」，不會默默遺失；可之後用離線重轉補齊。
- 按下停止時會把最後一句也送去辨識，並把整份即時逐字稿另存為一筆「離線即時」結果（不覆蓋其他稿）。
- 同時間只會載入一個大型模型；錄音中無法用另一個模型做離線重轉。

## 離線重轉（已存的錄音）
播放畫面 ⋮ →「逐字稿／雲端重轉／摘要」→「離線重新轉錄（手機運算，不上傳）」→ 選擇已下載的模型。結果另存，可與雲端稿並排比較、拿來摘要。

## 驗證狀態
- 已在 Linux x64 用同版 sherpa-onnx 2.0.1 JNI、與 App 相同的 Kotlin 設定跑過三個模型（中英夾雜樣本皆有正確輸出）。
- 尚未在 S26U 實機驗證速度、記憶體、發熱。

## 1.7.2：設定裡直接看手機資源與精確模型名稱

轉錄設定頂部顯示模型儲存磁碟的可用／總量（StatFs，app filesDir）、手機實際可用／總 RAM、系統低記憶體狀態與門檻
（ActivityManager.MemoryInfo），採 GiB 二進位單位，不是廣告標示的 GB。每 5 秒更新一次並可按「立即更新」；僅在組件存在且
畫面 lifecycle STARTED 時執行，離開／進入背景取消輪詢。全部磁碟與記憶體讀取在 IO dispatcher，不建立背景工作或常駐監控。

每張卡片顯示完整 checkpoint 名稱及版本（Vosk 直接取官方下載 archive 的 basename，例如 `vosk-model-small-en-us-0.15`），
區分「手機離線串流辨識」與「手機離線分句辨識」，兩者皆非雲端。

卡片同時顯示需求：下載空間含暫存與保留量（archive + extracted + 200 MiB；逐檔下載為 remaining + 3.5 GiB），
1.7B 續傳只計算 manifest 中符合長度的完成檔／有效 `.part` 長度，不算不明檔案。已安裝模型載入不需額外下載空間。
RAM 估計使用與載入檢查同一個 `ResourcePolicy` 公式（ONNX weights × 1.3 + 512 MiB），已安裝模型讀實際權重大小，
Vosk 沒有可靠的載入前 RAM 估計則顯示未知；系統低記憶體／32 位元大型模型限制也會標示。需求不是峰值保證，已載入模型已占用的記憶體亦會減少可用值。
下載空間明確不足時下載按鈕停用，未知時不新增限制，下載／載入開始時仍會重查。

English (India) / en-IN 不再提供；en-US 與其餘模型保留。升級時舊 en-IN 選擇會清空並提示重新選擇（錄音設定不變），
舊的模型檔案與資料庫列不自動刪除。設定頁亦持續顯示此說明。

未宣稱手機資源數值、手機效能或雲端 API 已經驗證；沒有手機截圖。本版保留原 Alpha 標示，未擅自宣稱穩定。
