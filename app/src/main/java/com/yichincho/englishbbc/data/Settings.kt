package com.yichincho.englishbbc.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

val DEFAULT_FEEDS = listOf(
    Feed("Global News Podcast", "https://podcasts.files.bbci.co.uk/p02nq0gn.rss"),
    Feed("Learning English from the News", "https://podcasts.files.bbci.co.uk/p05hw4bq.rss"),
    Feed("6 Minute English", "https://podcasts.files.bbci.co.uk/p02pc9tn.rss"),
    Feed("Business Daily", "https://podcasts.files.bbci.co.uk/p002vsxs.rss"),
    Feed("World Business Report", "https://podcasts.files.bbci.co.uk/p02tb8vq.rss"),
    Feed("Six O'Clock News", "https://podcasts.files.bbci.co.uk/b006qjxt.rss"),
)

enum class Provider(val label: String) { GEMINI("Gemini"), NVIDIA("NVIDIA"), DEEPSEEK("DeepSeek") }

/** Popular models offered as one-tap choices before the key has been tested. */
val PRESET_MODELS: Map<Provider, List<String>> = mapOf(
    Provider.NVIDIA to listOf(
        "deepseek-ai/deepseek-v4.1-flash",
        "z-ai/glm-5.3",
        "z-ai/glm-5.3-flash",
        "nvidia/nemotron-3-super-120b-a12b",
        "nvidia/nemotron-3-ultra-550b-a55b",
    ),
)

data class Settings(
    val geminiKey: String = "",
    val geminiModel: String = "gemini-flash-latest",
    val nvidiaKey: String = "",
    val nvidiaModel: String = "deepseek-ai/deepseek-v4.1-flash",
    val deepseekKey: String = "",
    val deepseekModel: String = "deepseek-chat",
    /** "auto" or a [Provider] name. */
    val translator: String = "auto",
    /** Minutes since midnight. */
    val nightStartMinute: Int = 17 * 60 + 30,
    val dayStartMinute: Int = 6 * 60,
    val cycleStartEpochDay: Long = 0,
    val fontSizeSp: Int = 16,
    val showZh: Boolean = true,
    /** Single-episode loop: when an episode ends it starts again from the top. */
    val loopEpisode: Boolean = false,
    val enabledFeeds: Set<String> = setOf(DEFAULT_FEEDS[0].url, DEFAULT_FEEDS[1].url),
    val customFeeds: List<Feed> = emptyList(),
) {
    fun key(p: Provider) = when (p) {
        Provider.GEMINI -> geminiKey
        Provider.NVIDIA -> nvidiaKey
        Provider.DEEPSEEK -> deepseekKey
    }

    fun model(p: Provider) = when (p) {
        Provider.GEMINI -> geminiModel
        Provider.NVIDIA -> nvidiaModel
        Provider.DEEPSEEK -> deepseekModel
    }

    fun withKey(p: Provider, v: String) = when (p) {
        Provider.GEMINI -> copy(geminiKey = v)
        Provider.NVIDIA -> copy(nvidiaKey = v)
        Provider.DEEPSEEK -> copy(deepseekKey = v)
    }

    fun withModel(p: Provider, v: String) = when (p) {
        Provider.GEMINI -> copy(geminiModel = v)
        Provider.NVIDIA -> copy(nvidiaModel = v)
        Provider.DEEPSEEK -> copy(deepseekModel = v)
    }

    /** Which provider translates: the chosen one if it has a key, otherwise the first that does. */
    fun translatorProvider(): Provider? {
        val chosen = Provider.entries.firstOrNull { it.name == translator && key(it).isNotBlank() }
        return chosen ?: listOf(Provider.DEEPSEEK, Provider.NVIDIA, Provider.GEMINI).firstOrNull { key(it).isNotBlank() }
    }

    fun allFeeds(): List<Feed> = DEFAULT_FEEDS + customFeeds

    fun visibleFeeds(): List<Feed> = allFeeds().filter { it.url in enabledFeeds }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        if (!prefs.contains("cycleStartEpochDay")) {
            prefs.edit().putLong("cycleStartEpochDay", LocalDate.now().toEpochDay()).apply()
        }
        val custom = JSONArray(prefs.getString("customFeeds", "[]"))
            .mapObjects { Feed(it.optString("title"), it.optString("url")) }
        return Settings(
            geminiKey = prefs.getString("geminiKey", d.geminiKey)!!,
            geminiModel = prefs.getString("geminiModel", d.geminiModel)!!,
            nvidiaKey = prefs.getString("nvidiaKey", d.nvidiaKey)!!,
            nvidiaModel = prefs.getString("nvidiaModel", d.nvidiaModel)!!,
            deepseekKey = prefs.getString("deepseekKey", d.deepseekKey)!!,
            deepseekModel = prefs.getString("deepseekModel", d.deepseekModel)!!,
            translator = prefs.getString("translator", d.translator)!!,
            nightStartMinute = prefs.getInt("nightStartMinute", d.nightStartMinute),
            dayStartMinute = prefs.getInt("dayStartMinute", d.dayStartMinute),
            cycleStartEpochDay = prefs.getLong("cycleStartEpochDay", 0),
            fontSizeSp = prefs.getInt("fontSizeSp", d.fontSizeSp),
            showZh = prefs.getBoolean("showZh", d.showZh),
            loopEpisode = prefs.getBoolean("loopEpisode", d.loopEpisode),
            enabledFeeds = prefs.getStringSet("enabledFeeds", d.enabledFeeds)!!.toSet(),
            customFeeds = custom,
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("geminiKey", s.geminiKey).putString("geminiModel", s.geminiModel)
            .putString("nvidiaKey", s.nvidiaKey).putString("nvidiaModel", s.nvidiaModel)
            .putString("deepseekKey", s.deepseekKey).putString("deepseekModel", s.deepseekModel)
            .putString("translator", s.translator)
            .putInt("nightStartMinute", s.nightStartMinute).putInt("dayStartMinute", s.dayStartMinute)
            .putLong("cycleStartEpochDay", s.cycleStartEpochDay)
            .putInt("fontSizeSp", s.fontSizeSp).putBoolean("showZh", s.showZh)
            .putBoolean("loopEpisode", s.loopEpisode)
            .putStringSet("enabledFeeds", s.enabledFeeds)
            .putString("customFeeds", s.customFeeds.toJsonArray { JSONObject().put("title", it.title).put("url", it.url) }.toString())
            .apply()
    }
}
