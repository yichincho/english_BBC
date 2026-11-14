# english_BBC

BBC 英文聽力練習 App（Android）。聽不懂的地方，有逐句原稿和中文可以看。

## 它會做什麼

- 從 BBC podcast 的 RSS 抓節目，按「加入」就下載聲音。
- BBC 的 RSS 沒有附原稿，所以用 Gemini 聽聲音、寫出帶時間的逐句原稿。
- 「今天」頁右上角的版本號（例如 v1.4.7）跟 Releases 標題上的一樣，就代表裝到的是最新版。
- 播放時目前這一句會亮起來；點任何一句就從那句重聽，也能單句重複。
- 用 DeepSeek（官方或 NVIDIA 上的 deepseek-v4.1）把每句翻成繁體中文，並標出難字。
- 速度規則：白天固定 1.0x；晚上 1.2 → 1.4 → 1.6 → 1.8 → 2.0，五天一輪，然後重來。

## 安裝

到 [Releases](https://github.com/yichincho/english_BBC/releases/latest) 下載 `english-bbc.apk`，在手機上安裝（需要 Android 8.0 以上）。

第一次打開後，到「設定」貼上 API 金鑰，按「測試金鑰」：

| 金鑰 | 用途 | 一定要嗎 |
| --- | --- | --- |
| Gemini | 聽聲音、寫原稿 | 要，沒有就只有聲音 |
| DeepSeek 或 NVIDIA | 翻中文、解釋單字 | 不一定，沒有的話會改用 Gemini 翻 |

金鑰只存在手機裡，不會進到這個 repo。

## 已知限制

- AI 寫的原稿不是 100% 正確，人名、地名偶爾會錯；時間只精確到秒，亮起來的句子可能差一兩秒。
- 做原稿的時候要讓 App 開著。原稿每 10 分鐘的聲音存一次，中途斷掉再按「再試一次」會從斷掉的地方接續。

## AI 模型同步與調用

本專案的 AI 模型清單（Gemini / NVIDIA / DeepSeek）統一依賴 **[yichincho/ai-models](https://github.com/yichincho/ai-models)**。

- **線上即時調用**：App 每次啟動時，會透過 `AiCatalog.kt` 直接拉取線上的 [`models.json`](https://raw.githubusercontent.com/yichincho/ai-models/main/models.json)。因此當模型更新、新增或下架時，**完全不需要重新編譯或重裝 APK**，App 就會自動切換為最新可用的模型。
- **離線與 APK 建置保證**：App 內附 `app/src/main/assets/models.json` 作為離線備援。GitHub Actions 每次建置 APK 時，亦會自動拉取最新清單打包進 APK。
- **如何請求更新模型清單？**：
  只要隨時向 AI 助手發送以下指令之一：
  > **「幫我更新 ai-models」** 或 **「幫我檢查目前最新可用的免費模型」**

  AI 會自動檢測各大廠商最新免費可用端點、更新 `ai-models` 倉庫並同步推送到 GitHub，手機端下次開啟 App 即享最新模型。

## 開發

```bash
./gradlew testDebugUnitTest assembleRelease
```

APK 在 `app/build/outputs/apk/release/`。

`app/src/main/java/ai/models/` 是從 [yichincho/ai-models](https://github.com/yichincho/ai-models) 複製來的呼叫程式，若呼叫介面有調整請先改 ai-models 再同步過來。推到 `main` 之後，GitHub Actions 會自動建置並更新 Releases。

`signing/app.keystore` 是故意放進 repo 的固定簽章檔（密碼就寫在 `app/build.gradle.kts`），這樣每次建置出來的 APK 都能直接蓋掉舊版、不用先移除。它不是機密；如果哪天要上架商店，請換成自己保管的金鑰。
