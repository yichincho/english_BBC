package com.yichincho.englishbbc.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.yichincho.englishbbc.data.AiException
import com.yichincho.englishbbc.data.Gemini
import com.yichincho.englishbbc.data.OpenAiCompat
import com.yichincho.englishbbc.data.Provider
import com.yichincho.englishbbc.data.Settings
import com.yichincho.englishbbc.data.TRUNCATED
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

/**
 * Finds the JSON object in a model reply. Models wrap it in thinking, Markdown fences or chatter, and sometimes
 * leave a trailing comma; a reply cut off mid-object cannot be saved and is reported as such.
 */
internal object JsonRescue {
    fun extract(raw: String): JSONObject {
        var text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        // An unclosed <think> means the thinking ran on; any answer would come after the last closing brace anyway.
        text = text.replace("<think>", "").replace(Regex("```(?:json)?"), "").trim()
        val candidates = balancedObjects(text)
        if (candidates.isEmpty()) {
            require(text.indexOf('{') < 0) { "AI 的回覆在 JSON 中途斷掉了（多半是輸出太長）" }
            throw IllegalArgumentException("AI 沒有回 JSON：${preview(text)}")
        }
        // The answer is normally the last object; earlier ones tend to be examples inside the thinking.
        for (c in candidates.asReversed()) {
            parse(c)?.let { return it }
            parse(c.replace(Regex(",\\s*([}\\]])"), "$1"))?.let { return it }
        }
        throw IllegalArgumentException("AI 回傳的 JSON 格式不正確：${preview(candidates.last())}")
    }

    private fun parse(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

    private fun preview(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= 80) flat else flat.take(50) + " … " + flat.takeLast(25)
    }

    /** Top-level `{…}` spans, skipping braces inside strings. */
    private fun balancedObjects(text: String): List<String> {
        val found = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for ((i, ch) in text.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> if (depth > 0) inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> if (depth > 0) { depth--; if (depth == 0) found += text.substring(start, i + 1) }
            }
        }
        return found
    }
}

/** One clause or phrase hung on a core part (or on another node), with what it adds there. */
internal data class DnaNode(
    val source: String,
    val kind: String,
    val function: String,
    val zh: String,
    val children: List<DnaNode> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", source).put("kind", kind).put("function", function).put("zh", zh)
        .put("children", JSONArray().also { arr -> children.forEach { arr.put(it.toJson()) } })

    fun count(): Int = 1 + children.sumOf { it.count() }
    fun depth(): Int = 1 + (children.maxOfOrNull { it.depth() } ?: 0)
}

/** One slot of the basic pattern: S, V, O, IO, DO, C, OC, or A for a sentence-level adverbial. */
internal data class CorePart(val label: String, val text: String, val zh: String, val children: List<DnaNode>) {
    fun toJson(): JSONObject = JSONObject()
        .put("label", label).put("text", text).put("zh", zh)
        .put("children", JSONArray().also { arr -> children.forEach { arr.put(it.toJson()) } })
}

internal data class SentenceDna(
    val pattern: String,
    val core: List<CorePart>,
    val skeletonZh: String,
    val summaryZh: String,
    val simpleEnglish: String,
    /** True when this came from the older flat V2 analysis and has no nested layers. */
    val legacy: Boolean = false,
) {
    /** Where each V slot sits in [english], walking the core in order so "is" never matches inside "this". */
    fun verbRanges(english: String): List<IntRange> {
        val found = mutableListOf<IntRange>()
        var offset = 0
        for (part in core) {
            if (part.text.isBlank()) continue
            val at = english.indexOf(part.text, offset).takeIf { it >= 0 } ?: wordIndex(english, part.text)
            if (at < 0) continue
            if (part.label == "V") found += at until at + part.text.length
            // A slots of the older flat analysis may sit anywhere, so they do not move the cursor.
            if (part.label != "A") offset = at + part.text.length
        }
        return found
    }

    private fun wordIndex(english: String, text: String): Int =
        Regex("(?<![A-Za-z'’])" + Regex.escape(text) + "(?![A-Za-z'’])").find(english)?.range?.first ?: -1

    /** The whole analysis as indented text, for pasting into notes. */
    fun toPlainText(english: String): String = buildString {
        appendLine(english)
        appendLine()
        appendLine("基本句型：$pattern　$skeletonZh")
        fun node(n: DnaNode, depth: Int) {
            val head = listOf(n.kind, n.function).filter { it.isNotBlank() }.joinToString(" · ")
            appendLine("${"  ".repeat(depth)}└ [$head] ${n.source}　${n.zh}")
            n.children.forEach { node(it, depth + 1) }
        }
        core.forEach { part ->
            appendLine("${part.label}：${part.text}${if (part.zh.isNotBlank()) "　${part.zh}" else ""}")
            part.children.forEach { node(it, 1) }
        }
        appendLine()
        appendLine("濃縮中文：$summaryZh")
        append("簡化英文：$simpleEnglish")
    }

    fun toJson(): String = JSONObject()
        .put("pattern", pattern)
        .put("core", JSONArray().also { arr -> core.forEach { arr.put(it.toJson()) } })
        .put("skeletonZh", skeletonZh).put("summaryZh", summaryZh).put("simpleEnglish", simpleEnglish)
        .put("legacy", legacy).toString()

    companion object {
        private val patterns = setOf("SV", "SVC", "SVO", "SVOO", "SVOC")
        val labels = setOf("S", "V", "O", "IO", "DO", "C", "OC", "A")
        const val MAX_DEPTH = 4
        const val MAX_NODES = 14

        private fun objectOf(raw: String): JSONObject = JsonRescue.extract(raw)

        private fun JSONObject.field(name: String): String = optString(name).trim().also {
            require(it.isNotEmpty()) { "AI 缺少必要欄位：$name" }
        }

        private fun nodes(arr: JSONArray?, original: String, level: Int): List<DnaNode> {
            if (arr == null || arr.length() == 0) return emptyList()
            require(level <= MAX_DEPTH) { "AI 的層次超過 $MAX_DEPTH 層" }
            return (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("子句格式錯誤")
                val source = o.optString("source").trim()
                val kind = o.optString("kind").trim()
                val zh = o.optString("zh").trim()
                require(source.isNotBlank() && kind.isNotBlank() && zh.isNotBlank()) { "子句欄位不可空白" }
                require(original.contains(source)) { "AI 修改了原文語塊，請重試" }
                DnaNode(source, kind, o.optString("function").trim(), zh, nodes(o.optJSONArray("children"), original, level + 1))
            }
        }

        fun parse(raw: String, original: String): SentenceDna {
            val obj = objectOf(raw)
            val pattern = obj.field("pattern")
            require(pattern in patterns) { "AI 回傳非五大基本句型：$pattern" }
            val arr = obj.optJSONArray("core") ?: throw IllegalArgumentException("AI 缺少主幹 core")
            val core = (0 until arr.length()).map { i ->
                val o = arr.optJSONObject(i) ?: throw IllegalArgumentException("主幹格式錯誤")
                val label = o.optString("label").trim().uppercase()
                val text = o.optString("text").trim()
                require(label in labels) { "主幹標籤不正確：$label" }
                require(text.isNotBlank()) { "主幹欄位不可空白" }
                require(original.contains(text)) { "主幹「$label」無法在原文中定位" }
                CorePart(label, text, o.optString("zh").trim(), nodes(o.optJSONArray("children"), original, 1))
            }
            require(core.any { it.label == "S" } && core.any { it.label == "V" }) { "主幹至少要有 S 與 V" }
            // Core slots are listed as they appear in the transcript.
            var offset = 0
            for (part in core) {
                val found = original.indexOf(part.text, offset)
                require(found >= 0) { "AI 主幹順序與原文不同" }
                offset = found + part.text.length
            }
            val total = core.sumOf { p -> p.children.sumOf { it.count() } }
            require(total <= MAX_NODES) { "AI 拆得太碎（$total 個子句）" }
            val simple = obj.field("simpleEnglish")
            require(simple.length <= 500) { "簡化英文過長" }
            return SentenceDna(pattern, core, obj.field("skeletonZh"), obj.field("summaryZh"), simple,
                obj.optBoolean("legacy", false))
        }

        /** Reads a V2 flat analysis so sentences already paid for keep showing without a new call. */
        fun fromLegacy(raw: String, original: String): SentenceDna {
            val obj = objectOf(raw)
            val pattern = obj.field("pattern")
            require(pattern in patterns) { "舊版句型不正確" }
            val chunks = (obj.optJSONArray("chunks") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                    DnaNode(it.optString("source").trim(), it.optString("role").trim(), "", it.optString("zh").trim())
                }.filter { it.source.isNotBlank() && original.contains(it.source) }
            }
            val restLabel = when (pattern) { "SVC" -> "C"; "SV" -> "A"; else -> "O" }
            val core = buildList {
                add(CorePart("S", obj.field("subject"), "", emptyList()))
                add(CorePart("V", obj.field("verb"), "", emptyList()))
                obj.optString("remainder").trim().takeIf { it.isNotBlank() }?.let { add(CorePart(restLabel, it, "", emptyList())) }
            }
            val withChunks = if (chunks.isEmpty()) core else core + CorePart("A", "", "其他修飾", chunks)
            return SentenceDna(pattern, withChunks, obj.field("skeletonZh"), obj.field("summaryZh"),
                obj.field("simpleEnglish"), legacy = true)
        }
    }
}

internal object SentenceDnaService {
    // Versioned cache prevents an older schema from being parsed as the current one.
    private const val VERSION = "dna-v3"
    private const val LEGACY_VERSION = "dna-v2"
    private const val PREFS = "sentence_dna_v3"
    private const val LEGACY_PREFS = "sentence_dna_v2"
    /** Room for a reasoning model's thinking plus a four-level tree. */
    private const val MAX_TOKENS = 8192
    private const val SYSTEM = """你是協助台灣學生閱讀 BBC 的英文文法老師。請把「一個英文長句」拆成一層一層的結構樹。
第一層 core：主句的五大基本句型 SV/SVC/SVO/SVOO/SVOC，依原文順序列出每個槽位：
  label 只能是 S、V、O、IO、DO、C、OC，或 A（句首／句尾的副詞子句、呼語、插入語等句子層級修飾）。
  text 是該槽位在原文中「完整」的逐字片段（例如 C 是 that 名詞子句時，text 要包含整個 that 子句及其後所有附屬成分）。
第二層以後 children：說明這個槽位裡面又包了什麼——名詞子句、關係子句（形容詞子句）、副詞子句（條件／原因／讓步／時間／目的）、分詞片語、不定詞片語、介系詞片語、同位語、比較結構。
  每個 child 的 source 是 parent 範圍內的逐字片段；若 child 裡面還有子句，就再放進它自己的 children，最多 4 層。
  kind 寫結構種類（例如「名詞子句（that）」「副詞子句（原因 because）」「關係子句」「介系詞片語」）。
  function 寫它在上一層扮演的角色（例如「作 surprised 的受詞」「修飾 movement」「說明為什麼」）。
  zh 寫這段的繁體中文意思。
僅回傳一個 JSON 物件，格式：
{"pattern":"SVC","core":[{"label":"S","text":"原文片段","zh":"中文","children":[{"source":"原文片段","kind":"名詞子句（what）","function":"作主詞","zh":"中文","children":[]}]},{"label":"V","text":"is","zh":"是","children":[]}],"skeletonZh":"一句繁體中文主幹意思","summaryZh":"一句繁中摘要","simpleEnglish":"一至兩句簡明自然英文重述"}
重要限制：
1. text / source 必須是原文中逐字存在且連續的片段，不可改字、拼接或補字；口語重複（例如 what what）照原文保留。原文裡的雙引號在 JSON 字串中要寫成 \"。
2. 只拆真正影響理解的結構，children 全部加起來不超過 12 個；單字或很短的片語不要拆。
3. 不要把引號內的子句誤認為外層主句；that 名詞子句作受詞或補語時，pattern 仍是 SVO 或 SVC。
4. summaryZh 保留轉折、否定、因果、條件，不得添加新事實；simpleEnglish 用更簡單的字與更短的句子重述，不要改變原意。
5. 只回 JSON，沒有 Markdown、開場語或註解。"""

    private fun cacheKey(version: String, id: String, start: Long, english: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$version|$id|$start|$english".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Analyses fetched while the app is open, so the transcript can mark their verbs right away. */
    val fresh = mutableStateMapOf<String, SentenceDna>()

    fun key(id: String, start: Long, english: String) = cacheKey(VERSION, id, start, english)

    /** Requests already on the way, so tapping again (or scrolling back) never pays for a second call. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<SentenceDna>>()

    fun cached(context: Context, id: String, start: Long, english: String): SentenceDna? {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(cacheKey(VERSION, id, start, english), null)
            ?.let { raw -> runCatching { SentenceDna.parse(raw, english) }.getOrNull()?.let { return it } }
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(cacheKey(LEGACY_VERSION, id, start, english), null) ?: return null
        return runCatching { SentenceDna.fromLegacy(legacy, english) }.getOrNull()
    }

    suspend fun load(
        context: Context, id: String, start: Long, english: String, settings: Settings, refresh: Boolean = false,
    ): SentenceDna {
        if (!refresh) cached(context, id, start, english)?.takeIf { !it.legacy }?.let { return it }
        val key = cacheKey(VERSION, id, start, english)
        val mine = CompletableDeferred<SentenceDna>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) return running.await()
        return try {
            fetch(context, key, english, settings).also {
                fresh[key] = it
                mine.complete(it)
            }
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key)
        }
    }

    private suspend fun fetch(context: Context, key: String, english: String, settings: Settings): SentenceDna =
        withContext(Dispatchers.IO) {
            val provider = settings.translatorProvider()
                ?: error("請先在設定中填入 Gemini、DeepSeek 或 NVIDIA API 金鑰")
            val model = settings.model(provider)
            fun request(prompt: String): String =
                if (provider == Provider.GEMINI)
                    Gemini.chatJson(settings.key(provider), model, SYSTEM, prompt, 150, failOnTruncation = true)
                else OpenAiCompat.chat(provider, settings.key(provider), model, SYSTEM,
                    prompt, maxTokens = MAX_TOKENS, timeoutSec = 150, failOnTruncation = true)
            fun attempt(prompt: String): SentenceDna = SentenceDna.parse(request(prompt), english)
            val analysis = try {
                attempt(english)
            } catch (first: Exception) {
                if (first is AiException && first.httpCode != TRUNCATED) throw first
                // One bounded retry; never cache an invalid model response. A cut-off reply gets a smaller job.
                val fix = if (first is AiException) "上次輸出太長被截斷。這次請精簡：children 總數不超過 6 個，zh 每項不超過 15 字。"
                else "先前輸出無法通過格式或原文驗證（${first.message}）。"
                try {
                    attempt("$fix 請嚴格依 system 的 JSON 格式重新分析此原文，只回 JSON：\n$english")
                } catch (second: Exception) {
                    // Name the model, so a model that keeps failing is easy to spot and swap in Settings.
                    throw IllegalArgumentException("${second.message}（${provider.label} · $model）")
                }
            }
            // commit() so the result is on disk before we report success; a killed app must not lose a paid answer.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, analysis.toJson()).commit()
            analysis
        }
}

/** Colors for the layers: depth 0 is the core slot, deeper layers move through the palette. */
@Composable
private fun layerColor(depth: Int): Color = when (depth % 4) {
    0 -> MaterialTheme.colorScheme.primary
    1 -> MaterialTheme.colorScheme.tertiary
    2 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.error
}

private fun labelName(label: String) = when (label) {
    "S" -> "S 主詞"
    "V" -> "V 動詞"
    "O" -> "O 受詞"
    "IO" -> "IO 間接受詞"
    "DO" -> "DO 直接受詞"
    "C" -> "C 補語"
    "OC" -> "OC 受詞補語"
    else -> "A 修飾"
}

/** A row with a colored bar on the left; nested rows add one more bar, so the layers read like a tree. */
@Composable
private fun LayerRow(depth: Int, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        repeat(depth + 1) { level ->
            Box(Modifier.padding(end = 6.dp).width(3.dp).fillMaxHeight().background(layerColor(level).copy(alpha = 0.6f)))
        }
        Column(Modifier.weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun NodeView(node: DnaNode, depth: Int) {
    LayerRow(depth) {
        Text(
            listOf(node.kind, node.function).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = layerColor(depth),
        )
        Text(node.source)
        Text(node.zh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    node.children.forEach { NodeView(it, depth + 1) }
}

@Composable
private fun StructureView(b: SentenceDna) {
    Text("基本句型：${b.pattern}", style = MaterialTheme.typography.titleSmall)
    Text(b.skeletonZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
    b.core.forEach { part ->
        LayerRow(0) {
            Text(labelName(part.label), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = layerColor(0))
            if (part.text.isNotBlank()) {
                if (part.label == "V") Text(part.text, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                else Text(part.text)
            }
            if (part.zh.isNotBlank()) {
                Text(part.zh, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        part.children.forEach { NodeView(it, 1) }
    }
}

@Composable
internal fun LongSentenceBreakdown(episodeId: String, startMs: Long, english: String, settings: Settings) {
    if (!LongSentenceDetector.qualifies(english)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copy = rememberCopier()
    var open by remember(episodeId, startMs, english) { mutableStateOf(false) }
    var tab by remember(episodeId, startMs, english) { mutableStateOf(0) }
    var result by remember(episodeId, startMs, english) {
        mutableStateOf(SentenceDnaService.cached(context, episodeId, startMs, english))
    }
    var busy by remember(episodeId, startMs, english) { mutableStateOf(false) }
    var error by remember(episodeId, startMs, english) { mutableStateOf<String?>(null) }
    fun run(refresh: Boolean) {
        busy = true
        error = null
        scope.launch {
            try { result = SentenceDnaService.load(context, episodeId, startMs, english, settings, refresh) }
            catch (e: Exception) { error = e.message ?: "拆解失敗，請稍後重試" }
            finally { busy = false }
        }
    }
    val saved = result != null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip(
            onClick = {
                open = !open
                // A saved analysis (even an older flat one) is shown as is; only an explicit tap re-asks the AI.
                if (open && result == null && !busy) run(refresh = false)
            },
            label = {
                Text(when {
                    open -> "收起 DNA 拆解"
                    saved -> "DNA 拆解 ✓ 已存"
                    else -> "DNA 拆解"
                })
            },
            colors = if (saved) AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) else AssistChipDefaults.assistChipColors(),
        )
        if (open) {
            when {
                busy -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                    Text("正在一層一層拆解句子…")
                }
                result != null -> {
                    val b = result!!
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("結構樹", "重點").forEachIndexed { index, label ->
                                    AssistChip(onClick = { tab = index }, label = {
                                        Text(if (tab == index) "● $label" else label)
                                    })
                                }
                            }
                            when (tab) {
                                0 -> StructureView(b)
                                else -> {
                                    Text("濃縮中文", style = MaterialTheme.typography.titleSmall)
                                    Text(b.summaryZh)
                                    Text("簡化英文", style = MaterialTheme.typography.titleSmall)
                                    Text(b.simpleEnglish)
                                }
                            }
                            if (b.legacy) {
                                Text("這是舊版的平面拆解（已存，不另外花費）。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { run(refresh = true) }) { Text("改用分層拆解重新分析") }
                            }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            TextButton(onClick = { copy(b.toPlainText(english)) }) { Text("複製拆解") }
                        }
                    }
                }
                error != null -> {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { run(refresh = false) }) { Text("重試") }
                }
            }
        }
    }
}

/** The transcript sentence; once it has a DNA analysis, its main verb(s) show in bold red. */
@Composable
internal fun SentenceText(
    episodeId: String, startMs: Long, english: String,
    fontSize: TextUnit, lineHeight: TextUnit, fontWeight: FontWeight, color: Color,
) {
    val context = LocalContext.current
    val long = remember(english) { LongSentenceDetector.qualifies(english) }
    val key = remember(episodeId, startMs, english) { SentenceDnaService.key(episodeId, startMs, english) }
    val saved = remember(key) { if (long) SentenceDnaService.cached(context, episodeId, startMs, english) else null }
    val dna = SentenceDnaService.fresh[key] ?: saved
    val red = MaterialTheme.colorScheme.error
    val text = remember(dna, english, red) {
        val verbs = dna?.verbRanges(english).orEmpty()
        buildAnnotatedString {
            append(english)
            verbs.forEach { addStyle(SpanStyle(color = red, fontWeight = FontWeight.Bold), it.first, it.last + 1) }
        }
    }
    Text(text, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight, color = color)
}
