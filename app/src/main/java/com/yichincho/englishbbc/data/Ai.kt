package com.yichincho.englishbbc.data

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AiException(message: String, val httpCode: Int = 0) : Exception(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    val JSON = "application/json; charset=utf-8".toMediaType()

    /** Runs the request and returns the body, or throws an [AiException] with a short readable reason. */
    fun execute(request: Request, who: String): String {
        client.newCall(request).execute().use { resp ->
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
                404, 410 -> "（這個型號不存在或已下架，到設定按「測試金鑰」換一個）"
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
            if (attempt >= times || e.httpCode !in setOf(429, 500, 503)) throw e
            delay(waitMs * attempt)
        }
    }
}

/** "MM:SS", "H:MM:SS" or "MM:SS.s" → milliseconds, -1 if unreadable. Minutes may exceed 59. */
fun parseTimestampMs(s: String): Long {
    val parts = s.trim().split(":")
    if (parts.size !in 2..3) return -1
    val seconds = parts.last().toDoubleOrNull() ?: return -1
    val whole = parts.dropLast(1).map { it.toLongOrNull() ?: return -1 }
    val minutes = whole.fold(0L) { acc, v -> acc * 60 + v }
    return minutes * 60_000 + (seconds * 1000).toLong()
}

/** Parses `[{"s":..,"t":..}]`; a reply cut off mid-way keeps every complete entry. */
fun parseLenientArray(text: String): List<Pair<String, String>> {
    val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val arr = try {
        JSONArray(clean)
    } catch (e: JSONException) {
        val end = clean.lastIndexOf('}')
        if (end < 0) return emptyList()
        try {
            JSONArray(clean.substring(0, end + 1) + "]")
        } catch (e2: JSONException) {
            return emptyList()
        }
    }
    return arr.mapObjects { it.optString("s") to it.optString("t") }
}

private fun extractJsonObject(text: String): JSONObject {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) throw AiException("AI 回的內容看不懂")
    return try {
        JSONObject(text.substring(start, end + 1))
    } catch (e: JSONException) {
        throw AiException("AI 回的內容看不懂")
    }
}

object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com"
    private const val WHO = "Gemini"

    fun listModels(key: String): List<String> {
        val req = Request.Builder().url("$BASE/v1beta/models?pageSize=200").header("x-goog-api-key", key).build()
        val models = JSONObject(Http.execute(req, WHO)).optJSONArray("models") ?: JSONArray()
        return models.mapObjects { it }
            .filter { it.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
            .map { it.optString("name").removePrefix("models/") }
    }

    /** Uploads the audio with the Files API and returns its file URI once it is ready. */
    fun upload(key: String, file: File, mime: String): String {
        val start = Request.Builder().url("$BASE/upload/v1beta/files")
            .header("x-goog-api-key", key)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(JSONObject().put("file", JSONObject().put("display_name", file.name)).toString().toRequestBody(Http.JSON))
            .build()
        val uploadUrl = Http.client.newCall(start).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AiException("Gemini 不讓上傳（${resp.code}）：${resp.body?.string().orEmpty().take(200)}", resp.code)
            }
            resp.header("x-goog-upload-url") ?: throw AiException("Gemini 沒給上傳位置")
        }

        val send = Request.Builder().url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mime.toMediaType()))
            .build()
        var info = JSONObject(Http.execute(send, WHO)).getJSONObject("file")

        var waited = 0
        while (info.optString("state") == "PROCESSING" && waited < 60) {
            Thread.sleep(2000)
            waited++
            val poll = Request.Builder().url("$BASE/v1beta/${info.getString("name")}").header("x-goog-api-key", key).build()
            info = JSONObject(Http.execute(poll, WHO))
        }
        if (info.optString("state") == "FAILED") throw AiException("Gemini 讀不了這個聲音檔")
        return info.getString("uri")
    }

    private fun generate(key: String, model: String, body: JSONObject): String {
        val req = Request.Builder().url("$BASE/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val resp = JSONObject(Http.execute(req, WHO))
        val candidate = resp.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiException("Gemini 沒有回答（${resp.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()}）")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return parts.mapObjects { it }.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
    }

    /** Transcribes one time range of an uploaded audio file into timed sentences. */
    fun transcribeRange(
        key: String, model: String, fileUri: String, mime: String,
        fromSec: Int, toSec: Int, context: String,
    ): List<Sentence> {
        fun mmss(sec: Int) = "%02d:%02d".format(sec / 60, sec % 60)
        val prompt = """
            Transcribe the spoken English in this audio word for word, but ONLY the part from ${mmss(fromSec)} to ${mmss(toSec)}.
            Rules:
            - Verbatim. Do not summarize, paraphrase, translate or skip anything that is spoken in that part.
            - One entry per sentence. Split sentences longer than about 25 words at a natural clause boundary.
            - "s" is the moment the entry starts, as MM:SS counted from the very beginning of the whole audio file.
            - "t" is the text of the entry.
            - Leave out music, jingles and sound effects.
            Background to help you spell names correctly:
            $context
        """.trimIndent()

        val schema = JSONObject().put("type", "ARRAY").put(
            "items", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject()
                    .put("s", JSONObject().put("type", "STRING"))
                    .put("t", JSONObject().put("type", "STRING")))
                .put("required", JSONArray().put("s").put("t"))
        )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("fileData", JSONObject().put("mimeType", mime).put("fileUri", fileUri)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0)
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema))

        val slackMs = 5_000
        return parseLenientArray(generate(key, model, body))
            .map { (s, t) -> Sentence(parseTimestampMs(s), t.trim()) }
            .filter { it.en.isNotEmpty() && it.startMs >= 0 && it.startMs >= fromSec * 1000L - slackMs && it.startMs <= toSec * 1000L + slackMs }
            .sortedBy { it.startMs }
    }

    fun chatJson(key: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json"))
        return generate(key, model, body)
    }
}

/** DeepSeek and NVIDIA both speak the OpenAI chat-completions format. */
object OpenAiCompat {
    fun baseUrl(p: Provider) = when (p) {
        Provider.NVIDIA -> "https://integrate.api.nvidia.com/v1"
        Provider.DEEPSEEK -> "https://api.deepseek.com"
        Provider.GEMINI -> error("Gemini has its own API")
    }

    fun listModels(p: Provider, key: String): List<String> {
        val req = Request.Builder().url("${baseUrl(p)}/models").header("Authorization", "Bearer $key").build()
        val data = JSONObject(Http.execute(req, p.label)).optJSONArray("data") ?: JSONArray()
        return data.mapObjects { it.optString("id") }.filter { it.isNotBlank() }.sorted()
    }

    fun chat(p: Provider, key: String, model: String, system: String, user: String, maxTokens: Int = 4096): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        if (p == Provider.DEEPSEEK && "json" in system.lowercase()) {
            body.put("response_format", JSONObject().put("type", "json_object"))
        }
        val req = Request.Builder().url("${baseUrl(p)}/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        val resp = JSONObject(Http.execute(req, p.label))
        return resp.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw AiException("${p.label} 沒有回答")
    }
}

object Ai {
    fun listModels(p: Provider, key: String): List<String> =
        if (p == Provider.GEMINI) Gemini.listModels(key) else OpenAiCompat.listModels(p, key)

    /**
     * Checks the key and returns the models it can use. NVIDIA's model list is public, so there the key
     * (and the model) are proven with a one-word chat instead.
     */
    fun verify(p: Provider, key: String, model: String): List<String> {
        val models = listModels(p, key)
        if (p == Provider.NVIDIA) OpenAiCompat.chat(p, key, suggestModel(p, models, model), "Reply with OK.", "hi", maxTokens = 8)
        return models
    }

    /** Keeps [current] if the provider still offers it; providers retire models often, so otherwise pick a likely one. */
    fun suggestModel(p: Provider, models: List<String>, current: String): String {
        if (current in models || models.isEmpty()) return current
        val pick = when (p) {
            Provider.GEMINI -> {
                val unwanted = listOf("lite", "image", "tts", "live", "audio", "embedding", "robotics", "computer")
                models.firstOrNull { it == "gemini-flash-latest" }
                    ?: models.filter { "flash" in it && unwanted.none { u -> u in it } }.maxOrNull()
            }
            Provider.NVIDIA -> models.filter { it.startsWith("deepseek-ai/deepseek-v") }.maxOrNull()
            Provider.DEEPSEEK -> models.firstOrNull { "chat" in it } ?: models.firstOrNull { "flash" in it } ?: models.first()
        }
        return pick ?: current
    }

    private const val TRANSLATE_SYSTEM = """你是幫台灣學生練英文聽力的老師。我會給你一個 JSON，裡面是 BBC 節目的英文句子。
請把每一句翻成自然的繁體中文（台灣用語），並挑出 0 到 3 個對中級學習者比較難的單字或片語，附上簡短的繁體中文意思。
只輸出 JSON，格式：{"items":[{"i":0,"zh":"中文翻譯","words":[{"en":"單字","zh":"意思"}]}]}
i 要跟輸入的一樣，每一句都要有，不可以漏。"""

    /** Returns the sentences of [batch] with Chinese and vocabulary filled in; index = position in the transcript. */
    fun translate(s: Settings, p: Provider, batch: List<IndexedValue<Sentence>>): Map<Int, Sentence> {
        val user = JSONObject().put("sentences", batch.toJsonArray { JSONObject().put("i", it.index).put("en", it.value.en) }).toString()
        val reply = if (p == Provider.GEMINI) Gemini.chatJson(s.key(p), s.model(p), TRANSLATE_SYSTEM, user)
        else OpenAiCompat.chat(p, s.key(p), s.model(p), TRANSLATE_SYSTEM, user)

        val items = extractJsonObject(reply).optJSONArray("items") ?: JSONArray()
        val byIndex = batch.associate { it.index to it.value }
        return items.mapObjects { it }.mapNotNull { o ->
            val original = byIndex[o.optInt("i", -1)] ?: return@mapNotNull null
            val words = (o.optJSONArray("words") ?: JSONArray()).mapObjects { Word(it.optString("en"), it.optString("zh")) }
                .filter { it.en.isNotBlank() }
            o.optInt("i") to original.copy(zh = o.optString("zh"), words = words)
        }.toMap()
    }
}
