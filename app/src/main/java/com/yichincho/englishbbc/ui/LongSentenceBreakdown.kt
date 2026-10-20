package com.yichincho.englishbbc.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yichincho.englishbbc.data.Gemini
import com.yichincho.englishbbc.data.OpenAiCompat
import com.yichincho.englishbbc.data.Provider
import com.yichincho.englishbbc.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal object LongSentenceDetector {
    fun wordCount(text: String): Int = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?").findAll(text).count()
    fun qualifies(text: String): Boolean {
        val words = wordCount(text)
        if (words < 15) return false
        val markers = Regex("\\b(although|though|whereas|unless|because|which|whose|whom|who|that|whether|while)\\b", RegexOption.IGNORE_CASE)
            .findAll(text).count()
        return words >= 25 || markers >= 2
    }
}

internal data class GrammarChunk(val source: String, val role: String, val zh: String)
internal data class SentenceDna(
    val pattern: String,
    val subject: String,
    val verb: String,
    val remainder: String,
    val skeletonZh: String,
    val chunks: List<GrammarChunk>,
    val summaryZh: String,
    val simpleEnglish: String,
) {
    fun toJson(): String = JSONObject()
        .put("pattern", pattern).put("subject", subject).put("verb", verb)
        .put("remainder", remainder).put("skeletonZh", skeletonZh)
        .put("chunks", JSONArray().also { arr -> chunks.forEach { arr.put(JSONObject()
            .put("source", it.source).put("role", it.role).put("zh", it.zh)) } })
        .put("summaryZh", summaryZh).put("simpleEnglish", simpleEnglish).toString()

    companion object {
        private val patterns = setOf("SV", "SVC", "SVO", "SVOO", "SVOC")
        fun parse(raw: String, original: String): SentenceDna {
            // Ignore completed reasoning wrappers and Markdown fences.
            val clean = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            val start = clean.indexOf('{')
            val end = clean.lastIndexOf('}')
            require(start >= 0 && end > start) { "AI 未產生可辨識的 JSON，請重試或切換模型" }
            val obj = runCatching { JSONObject(clean.substring(start, end + 1)) }
                .getOrElse { throw IllegalArgumentException("AI 回傳的 JSON 格式不正確") }
            fun field(name: String): String = obj.optString(name).trim().also {
                require(it.isNotEmpty()) { "AI 缺少必要欄位：$name" }
            }
            val pattern = field("pattern")
            require(pattern in patterns) { "AI 回傳非五大基本句型：$pattern" }
            val arr = obj.optJSONArray("chunks") ?: throw IllegalArgumentException("AI 缺少 Chunks")
            require(arr.length() in 1..5) { "AI 的 Chunks 數量必須介於 1～5" }
            val chunks = (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("Chunk 格式錯誤")
                val source = o.optString("source").trim()
                val role = o.optString("role").trim()
                val zh = o.optString("zh").trim()
                require(source.isNotBlank() && role.isNotBlank() && zh.isNotBlank()) { "Chunk 欄位不可空白" }
                require(original.contains(source)) { "AI 修改了原文語塊，請重試" }
                GrammarChunk(source, role, zh)
            }
            // Each cited source fragment must appear in the same order as the transcript.
            var offset = 0
            for (chunk in chunks) {
                val found = original.indexOf(chunk.source, offset)
                require(found >= 0) { "AI 語塊順序與原文不同" }
                offset = found + chunk.source.length
            }
            val subject = field("subject")
            val verb = field("verb")
            require(original.contains(subject) && original.contains(verb)) { "主詞或動詞無法在原文中定位" }
            val simple = field("simpleEnglish")
            require(simple.length <= 500) { "簡化英文過長" }
            return SentenceDna(pattern, subject, verb, obj.optString("remainder").trim(),
                field("skeletonZh"), chunks, field("summaryZh"), simple)
        }
    }
}

internal object SentenceDnaService {
    // Versioned cache prevents stale V1 analysis from being mistaken for V2.
    private const val VERSION = "dna-v2"
    private const val SYSTEM = """你是協助台灣學生閱讀 BBC 的英文文法老師。
分析「一個英文長句」。先找主句的五大基本句型 SV/SVC/SVO/SVOO/SVOC；補充說明關係子句、名詞子句、形容詞子句、副詞子句、分詞片語如何附加在主幹。
不要逐字對齊，不要逐字翻譯，也不要用長篇教科書式解說。
僅回傳一個 JSON 物件，欄位 EXACTLY：
{"pattern":"SVO","subject":"原文中逐字存在的主詞文字","verb":"原文中逐字存在的主要動詞文字","remainder":"主句剩餘必要部分的簡短原文","skeletonZh":"一句繁體中文主幹意思","chunks":[{"source":"原文連續且完全相同的片段","role":"例如讓步子句／關係子句／名詞子句／分詞修飾","zh":"繁體中文語意"}],"summaryZh":"一句繁中摘要","simpleEnglish":"一至兩句簡明自然英文重述"}。
重要限制：
1. chunks 只選 1～5 個真正影響理解的子句／修飾語；source 必須為原文中逐字存在且連續的片段，順序與原文相同，不可拼接、改字或重疊。
2. subject / verb 也必須為原文逐字存在的連續文字。不要把引號內的子句誤認為外層主句。
3. 主句若有 that 名詞子句作受詞，pattern 仍可為 SVO；其他修飾結構不算新的五大句型。
4. summaryZh 保留轉折、否定、因果、條件，不得添加新事實；simpleEnglish 用更簡單的字與更短的句子重述，不要改變原意。
5. 只回 JSON，沒有 Markdown、開場語或註解。"""

    private fun cacheKey(id: String, start: Long, english: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$VERSION|$id|$start|$english".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun cached(context: Context, id: String, start: Long, english: String): SentenceDna? {
        val raw = context.getSharedPreferences("sentence_dna_v2", Context.MODE_PRIVATE)
            .getString(cacheKey(id, start, english), null) ?: return null
        return runCatching { SentenceDna.parse(raw, english) }.getOrNull()
    }

    suspend fun load(context: Context, id: String, start: Long, english: String, settings: Settings): SentenceDna =
        withContext(Dispatchers.IO) {
            cached(context, id, start, english)?.let { return@withContext it }
            val provider = settings.translatorProvider()
                ?: error("請先在設定中填入 Gemini、DeepSeek 或 NVIDIA API 金鑰")
            fun request(prompt: String): String =
                if (provider == Provider.GEMINI)
                    Gemini.chatJson(settings.key(provider), settings.model(provider), SYSTEM, prompt, 90)
                else OpenAiCompat.chat(provider, settings.key(provider), settings.model(provider), SYSTEM,
                    prompt, maxTokens = 2400, timeoutSec = 90)
            val raw = request(english)
            val analysis = runCatching { SentenceDna.parse(raw, english) }.getOrElse { firstError ->
                // One bounded retry; never cache an invalid model response.
                val second = request("先前輸出無法通過格式或原文驗證（${firstError.message}）。請嚴格依 system 的 JSON 格式重新分析此原文：\n$english")
                SentenceDna.parse(second, english)
            }
            context.getSharedPreferences("sentence_dna_v2", Context.MODE_PRIVATE)
                .edit().putString(cacheKey(id, start, english), analysis.toJson()).apply()
            analysis
        }
}

@Composable
internal fun LongSentenceBreakdown(episodeId: String, startMs: Long, english: String, settings: Settings) {
    if (!LongSentenceDetector.qualifies(english)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(episodeId, startMs, english) { mutableStateOf(false) }
    var tab by remember(episodeId, startMs, english) { mutableStateOf(0) }
    var result by remember(episodeId, startMs, english) {
        mutableStateOf(SentenceDnaService.cached(context, episodeId, startMs, english))
    }
    var busy by remember(episodeId, startMs, english) { mutableStateOf(false) }
    var error by remember(episodeId, startMs, english) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(
            onClick = {
                open = !open
                if (open && result == null && !busy) {
                    busy = true
                    error = null
                    scope.launch {
                        try { result = SentenceDnaService.load(context, episodeId, startMs, english, settings) }
                        catch (e: Exception) { error = e.message ?: "拆解失敗，請稍後重試" }
                        finally { busy = false }
                    }
                }
            },
            label = { Text(if (open) "收起 DNA 拆解" else "DNA 拆解") },
        )
        if (open) {
            when {
                busy -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                    Text("正在辨識句型與語塊…")
                }
                result != null -> {
                    val b = result!!
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("主幹", "Chunks", "重點").forEachIndexed { index, label ->
                                    AssistChip(onClick = { tab = index }, label = {
                                        Text(if (tab == index) "● $label" else label)
                                    })
                                }
                            }
                            when (tab) {
                                0 -> {
                                    Text("基本句型：${b.pattern}", style = MaterialTheme.typography.titleSmall)
                                    Text("S：${b.subject}")
                                    Text("V：${b.verb}")
                                    if (b.remainder.isNotBlank()) Text("其餘：${b.remainder}")
                                    Text(b.skeletonZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                1 -> b.chunks.forEach { chunk ->
                                    Text(chunk.role, style = MaterialTheme.typography.labelMedium)
                                    Text(chunk.source)
                                    Text(chunk.zh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                else -> {
                                    Text("濃縮中文", style = MaterialTheme.typography.titleSmall)
                                    Text(b.summaryZh)
                                    Text("簡化英文", style = MaterialTheme.typography.titleSmall)
                                    Text(b.simpleEnglish)
                                }
                            }
                        }
                    }
                }
                error != null -> {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try { result = SentenceDnaService.load(context, episodeId, startMs, english, settings) }
                            catch (e: Exception) { error = e.message ?: "拆解失敗" }
                            finally { busy = false }
                        }
                    }) { Text("重試") }
                }
            }
        }
    }
}
