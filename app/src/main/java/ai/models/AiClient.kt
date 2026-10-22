package ai.models

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiException(message: String, val httpCode: Int = 0) : Exception(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Runs the request and returns the body, or throws an [AiException] with a short readable reason.
     * With [timeoutSec] the whole call is limited to that long, and a call that runs out of time or loses the
     * connection also becomes an [AiException], so the caller can move on instead of waiting.
     */
    fun execute(request: Request, who: String, timeoutSec: Long = 0): String {
        val call = client.newCall(request)
        if (timeoutSec > 0) call.timeout().timeout(timeoutSec, TimeUnit.SECONDS)
        return try {
            read(call.execute(), who)
        } catch (e: IOException) {
            throw AiException(if (timeoutSec > 0) "$who 沒有在 $timeoutSec 秒內回覆（或連線斷了）" else "$who 連不上：${e.message}")
        }
    }

    private fun read(response: Response, who: String): String {
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) return body
            val detail = try {
                val o = JSONObject(body)
                o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: o.optString("detail").takeIf { it.isNotBlank() }
                    ?: body
            } catch (e: JSONException) {
                body
            }
            val hint = when (resp.code) {
                400, 401, 403 -> "（金鑰或型號可能不對）"
                404, 410 -> "（這個型號不存在或已下架，換一個型號）"
                429 -> "（用太快或額度用完了）"
                else -> ""
            }
            throw AiException("$who 回報錯誤 ${resp.code}$hint：${detail.take(200)}", resp.code)
        }
    }
}

/** Retries rate-limit and overload errors a few times; other errors go straight up. */
suspend fun <T> retrying(times: Int = 3, waitMs: Long = 20_000, block: () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: AiException) {
            attempt++
            if (attempt >= times || e.httpCode !in setOf(429, 500, 502, 503)) throw e
            delay(waitMs * attempt)
        }
    }
}

/** One provider and its key. All calls block; run them off the main thread. */
class AiClient(val spec: ProviderSpec, private val key: String) {
    private val who get() = spec.label

    fun listModels(): List<String> = when (spec.format) {
        ApiFormat.GEMINI -> {
            val req = Request.Builder().url("${spec.baseUrl}/v1beta/models?pageSize=1000").header("x-goog-api-key", key).build()
            JSONObject(Http.execute(req, who)).optJSONArray("models").objects()
                .filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                .map { it.optString("name").removePrefix("models/") }
        }
        ApiFormat.OPENAI -> {
            val req = Request.Builder().url("${spec.baseUrl}/models").header("Authorization", "Bearer $key").build()
            JSONObject(Http.execute(req, who)).optJSONArray("data").objects()
                .map { it.optString("id") }.filter { it.isNotBlank() }.sorted()
        }
    }

    /**
     * One system + user turn; returns the reply text with any `<think>…</think>` removed.
     * [json] asks for a JSON reply where the provider supports forcing it.
     */
    fun chat(
        model: String, system: String, user: String,
        json: Boolean = false, maxTokens: Int = 4096, temperature: Double = 0.2, timeoutSec: Long = 0,
    ): String {
        val raw = when (spec.format) {
            ApiFormat.GEMINI -> {
                val config = JSONObject().put("temperature", temperature)
                if (json) config.put("responseMimeType", "application/json")
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", config)
                if (system.isNotBlank()) {
                    body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                }
                val req = Request.Builder().url("${spec.baseUrl}/v1beta/models/$model:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody(Http.JSON)).build()
                val resp = JSONObject(Http.execute(req, who, timeoutSec))
                val candidate = resp.optJSONArray("candidates")?.optJSONObject(0)
                    ?: throw AiException("$who 沒有回答（${resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()}）")
                candidate.optJSONObject("content")?.optJSONArray("parts").objects()
                    .filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
            }
            ApiFormat.OPENAI -> {
                val messages = JSONArray()
                if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
                messages.put(JSONObject().put("role", "user").put("content", user))
                val body = JSONObject().put("model", model).put("temperature", temperature)
                    .put("max_tokens", maxTokens).put("messages", messages)
                // DeepSeek's own API enforces JSON; NVIDIA's models vary, so there the prompt has to ask for it.
                if (json && spec.name == "DEEPSEEK") body.put("response_format", JSONObject().put("type", "json_object"))
                val req = Request.Builder().url("${spec.baseUrl}/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody(Http.JSON)).build()
                val resp = JSONObject(Http.execute(req, who, timeoutSec))
                resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?: throw AiException("$who 沒有回答")
            }
        }
        return raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    /** Sends a one-word chat to [model]; null when it answers, otherwise the short reason it did not. */
    fun probe(model: String, timeoutSec: Long = 30): String? = try {
        chat(model, "", "Reply with OK.", maxTokens = 16, timeoutSec = timeoutSec)
        null
    } catch (e: Exception) {
        e.message ?: "沒有回應"
    }

    /** Checks every candidate in order; [onEach] gets null for a model that answered. */
    fun probeAll(models: List<String> = spec.models.map { it.id }, onEach: (String, String?) -> Unit) {
        models.forEach { onEach(it, probe(it)) }
    }
}

/**
 * Keeps [current] if the provider still offers it. Otherwise the first catalog candidate the provider lists, then
 * the catalog's recommendation, then [current] unchanged (an empty list means "could not tell").
 */
fun suggestModel(spec: ProviderSpec, available: List<String>, current: String): String {
    if (current in available || available.isEmpty()) return current
    return spec.models.firstOrNull { it.status != "fail" && it.id in available }?.id
        ?: spec.recommended.takeIf { it in available }
        ?: current
}
