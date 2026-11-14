# english_BBC

BBC 英文聽力練習 Android App。

## AI 模型清單維護
- 本專案所有 AI 模型清單（Gemini / NVIDIA / DeepSeek）統一來自共用專案 **`yichincho/ai-models`**。
- App 啟動時透過 `AiCatalog.kt` 直接線上拉取 `https://raw.githubusercontent.com/yichincho/ai-models/main/models.json`，離線備援與 APK 建置打包使用 `app/src/main/assets/models.json`。
- **維護觸發語句**：當用戶提到：
  - 「**幫我更新 ai-models**」
  - 「**幫我檢查目前最新可用的免費模型**」
  - 「**請檢查模型**」
  應當到 `yichincho/ai-models` 檢驗與更新 `models.json`，並同步更新至本專案的 `app/src/main/assets/models.json`。

## 建置與測試
- 單元測試：`./gradlew testDebugUnitTest`
- 打包發布：`./gradlew assembleRelease`
- 使用者介面語言與提示一律使用繁體中文（台灣）。
