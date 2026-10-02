# english_BBC

BBC 英文聽力練習 App（Android）。聽不懂的地方，有逐句原稿和中文可以看。

## 它會做什麼

- 從 BBC podcast 的 RSS 抓節目，按「加入」就下載聲音。
- BBC 的 RSS 沒有附原稿，所以用 Gemini 聽聲音、寫出帶時間的逐句原稿。
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
- 做原稿的時候要讓 App 開著（一集大約幾分鐘）。

## 開發

```bash
./gradlew testDebugUnitTest assembleRelease
```

APK 在 `app/build/outputs/apk/release/`。推到 `main` 之後，GitHub Actions 會自動建置並更新 Releases。

`signing/app.keystore` 是故意放進 repo 的固定簽章檔（密碼就寫在 `app/build.gradle.kts`），這樣每次建置出來的 APK 都能直接蓋掉舊版、不用先移除。它不是機密；如果哪天要上架商店，請換成自己保管的金鑰。
