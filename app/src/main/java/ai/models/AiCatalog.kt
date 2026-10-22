package ai.models

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How a provider is called: Gemini's own API, or the OpenAI chat-completions format (NVIDIA, DeepSeek, ...). */
enum class ApiFormat { GEMINI, OPENAI }

/** One model of [ProviderSpec.models]; [status] is "ok", "busy", "fail" or "unknown" (not checked yet). */
data class ModelEntry(
    val id: String,
    val status: String = "unknown",
    val reason: String = "",
    val latencyMs: Long? = null,
    val note: String = "",
) {
    val usable: Boolean get() = status == "ok" || status == "busy" || status == "unknown"
}

data class ProviderSpec(
    /** Key in models.json, e.g. "NVIDIA". */
    val name: String,
    val label: String,
    val format: ApiFormat,
    val baseUrl: String,
    /** Environment variable / secret name the key lives under, e.g. "NVIDIA_API_KEY". */
    val keyEnv: String,
    val recommended: String,
    /** In order of preference. */
    val models: List<ModelEntry>,
    /** New models the last check saw but nobody has added as candidates yet. */
    val discovered: List<String> = emptyList(),
) {
    /** Models to offer in a picker: ones that answered first, then unchecked ones; failed ones are left out. */
    fun choices(): List<String> =
        models.filter { it.status == "ok" }.map { it.id } +
            models.filter { it.status != "ok" && it.usable }.map { it.id }
}

data class Catalog(val updatedAt: String, val providers: Map<String, ProviderSpec>) {
    operator fun get(name: String): ProviderSpec? = providers[name]

    companion object {
        /** The shared list; every project reads the same file. */
        const val DEFAULT_URL = "https://raw.githubusercontent.com/yichincho/ai-models/main/models.json"

        fun parse(text: String): Catalog {
            val root = JSONObject(text)
            val providers = root.getJSONObject("providers")
            val specs = providers.keys().asSequence().associateWith { name ->
                val p = providers.getJSONObject(name)
                ProviderSpec(
                    name = name,
                    label = p.optString("label", name),
                    format = if (p.optString("format") == "gemini") ApiFormat.GEMINI else ApiFormat.OPENAI,
                    baseUrl = p.getString("baseUrl").trimEnd('/'),
                    keyEnv = p.optString("keyEnv"),
                    recommended = p.optString("recommended"),
                    models = p.optJSONArray("models").objects().map {
                        ModelEntry(
                            id = it.getString("id"),
                            status = it.optString("status", "unknown").ifBlank { "unknown" },
                            reason = it.optString("reason"),
                            latencyMs = if (it.has("latencyMs") && !it.isNull("latencyMs")) it.optLong("latencyMs") else null,
                            note = it.optString("note"),
                        )
                    },
                    discovered = p.optJSONArray("discovered").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) } },
                )
            }
            return Catalog(root.optString("updatedAt"), specs)
        }

        /**
         * Downloads the shared list and keeps a copy in [cacheFile]. Offline or on any error it falls back to the
         * cached copy, then to [bundled] (a copy of models.json shipped with the app), so this never throws.
         */
        fun load(cacheFile: File?, bundled: String, url: String = DEFAULT_URL, timeoutSec: Long = 10): Catalog {
            val fresh = runCatching {
                val text = Http.execute(Request.Builder().url(url).build(), "models.json", timeoutSec)
                parse(text).also { cacheFile?.writeText(text) }
            }.getOrNull()
            return fresh
                ?: cacheFile?.takeIf { it.exists() }?.let { runCatching { parse(it.readText()) }.getOrNull() }
                ?: parse(bundled)
        }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
