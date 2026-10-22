package com.yichincho.englishbbc.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekModelMigrationTest {
    @Test fun migrateOldChatAlias() {
        assertEquals("deepseek-flash", migrateLegacyDeepSeekModel("deepseek-chat"))
    }

    @Test fun migrateOldReasonerAlias() {
        assertEquals("deepseek-flash", migrateLegacyDeepSeekModel("deepseek-reasoner"))
    }

    @Test fun preserveCustomModelAndCurrentModel() {
        assertEquals("deepseek-flash", migrateLegacyDeepSeekModel("deepseek-flash"))
        assertEquals("deepseek-v4-pro", migrateLegacyDeepSeekModel("deepseek-v4-pro"))
        assertEquals("custom-model", migrateLegacyDeepSeekModel("custom-model"))
    }

    @Test fun defaultsUseCurrentOfficialAndExistingNvidiaModels() {
        val settings = Settings()
        assertEquals("deepseek-flash", settings.deepseekModel)
        assertEquals("deepseek-ai/deepseek-v4.1-flash", settings.nvidiaModel)
    }

    @Test fun modelSuggestionPrefersCurrentOfficialId() {
        val available = listOf("deepseek-v4-pro", "deepseek-flash", "deepseek-chat")
        assertEquals("deepseek-flash", Ai.suggestModel(Provider.DEEPSEEK, available, "not-listed"))
    }

    @Test fun geminiSuggestionPrefersFlash37ThenOlderFlash() {
        assertEquals("gemini-3.7-flash", Ai.suggestModel(Provider.GEMINI, listOf("gemini-3.8-flash", "gemini-3.7-flash"), "gone"))
        assertEquals("gemini-3.5-flash", Ai.suggestModel(Provider.GEMINI, listOf("gemini-3.8-flash", "gemini-3.5-flash"), "gone"))
    }

    @Test fun nvidiaFallsBackToGlmWhenNoDeepSeek() {
        assertEquals("z-ai/glm-5.3-flash", Ai.suggestModel(Provider.NVIDIA, listOf("z-ai/glm-5.3-flash", "openai/gpt-oss-20b"), "gone"))
    }
}
