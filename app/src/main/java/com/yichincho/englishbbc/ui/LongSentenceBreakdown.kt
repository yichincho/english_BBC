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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yichincho.englishbbc.data.Ai
import com.yichincho.englishbbc.data.Gemini
import com.yichincho.englishbbc.data.OpenAiCompat
import com.yichincho.englishbbc.data.Provider
import com.yichincho.englishbbc.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

/** First-pass local eligibility check: avoid charging the AI for normal short lines. */
internal object LongSentenceDetector {
    fun wordCount(text: String): Int = Regex("[A-Za-z]+(?:['’][A-Za-z]+)?").findAll(text).count()

    fun qualifies(text: String): Boolean {
        val words = wordCount(text)
        if (words < 15) return false
        val markers = Regex("\\b(although|though|whereas|unless|because|which|whose|whom|who|that|whether|while)\\b", RegexOption.IGNORE_CASE)
            .findAll(text).count()
        return words >= 25 || (words >= 15 && markers >= 2)
    }
}

internal data class SentenceBreakdown(
    val core: String,
    val coreZh: String,
    val context: String,
    val contextZh: String,
    val explanation: String,
    val fullZh: String,
) {
    fun toJson() = JSONObject().put("core", core).put("coreZh", coreZh)
        .put("context", context).put("contextZh", contextZh)
        .put("explanation", explanation).put("fullZh", fullZh).toString()

    companion object {
        fun parse(raw: String): SentenceBreakdown {
            val clean = raw.replace(Regex("(?s)<think>.*?</think>"), "").trim()
            val start = clean.indexOf('{')
            val end = clean.lastIndexOf('}')
            require(start >= 0 && end > start) { "AI 沒有回傳 JSON 物件" }
            val obj = JSONObject(clean.substring(start, end + 1))
            val result = SentenceBreakdown(
                obj.optString("core"), obj.optString("coreZh"),
                obj.optString("context"), obj.optString("contextZh"),
                obj.optString("explanation"), obj.optString("fullZh"),
            )
            require(result.core.isNotBlank() && result.coreZh.isNotBlank() && result.fullZh.isNotBlank()) {
                "AI 缺少必要的拆解欄位"
            }
            return result
        }
    }
}

internal object SentenceBreakdownService {
    private const val VERSION = "v1"
    private const val SYSTEM = """你是台灣 BBC 英文聽力教師。將指定的英文長句拆解為：1. 最核心的英文主句（可保留必要受詞子句）；2. 主句繁中意思；3. 其他背景、關係子句或分詞修飾的英文摘錄；4. 該部分繁中意思；5. 兩句以內解釋主詞、主要動詞及邏輯關係；6. 完整自然的繁中翻譯。請勿杜撰原文未包含的資訊。僅輸出 JSON 物件，欄位固定為 core, coreZh, context, contextZh, explanation, fullZh。所有中文使用台灣繁體中文。"""

    private fun cacheKey(episodeId: String, startMs: Long, english: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$VERSION|$episodeId|$startMs|$english".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun cached(context: Context, episodeId: String, startMs: Long, english: String): SentenceBreakdown? {
        val key = cacheKey(episodeId, startMs, english)
        val raw = context.getSharedPreferences("sentence_breakdown", Context.MODE_PRIVATE).getString(key, null)
            ?: return null
        return runCatching { SentenceBreakdown.parse(raw) }.getOrNull()
    }

    suspend fun load(context: Context, episodeId: String, startMs: Long, english: String, settings: Settings): SentenceBreakdown =
        withContext(Dispatchers.IO) {
            cached(context, episodeId, startMs, english)?.let { return@withContext it }
            val provider = settings.translatorProvider()
                ?: error("請先在設定中填入 Gemini、DeepSeek 或 NVIDIA API 金鑰")
            val raw = if (provider == Provider.GEMINI) {
                Gemini.chatJson(settings.key(provider), settings.model(provider), SYSTEM, english, 90)
            } else {
                OpenAiCompat.chat(provider, settings.key(provider), settings.model(provider), SYSTEM, english, maxTokens = 1200, timeoutSec = 90)
            }
            val result = SentenceBreakdown.parse(raw)
            context.getSharedPreferences("sentence_breakdown", Context.MODE_PRIVATE).edit()
                .putString(cacheKey(episodeId, startMs, english), result.toJson()).apply()
            result
        }
}

@Composable
internal fun LongSentenceBreakdown(episodeId: String, startMs: Long, english: String, settings: Settings) {
    if (!LongSentenceDetector.qualifies(english)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(episodeId, startMs, english) { mutableStateOf(false) }
    var phase by remember(episodeId, startMs, english) { mutableStateOf(0) }
    var result by remember(episodeId, startMs, english) {
        mutableStateOf(SentenceBreakdownService.cached(context, episodeId, startMs, english))
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
                        try {
                            result = SentenceBreakdownService.load(context, episodeId, startMs, english, settings)
                        } catch (e: Exception) {
                            error = e.message ?: "拆解失敗，請稍後重試"
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            label = { Text(if (open) "收起拆解" else "長句拆解") },
        )
        if (open) {
            when {
                busy -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                    Text("正在拆解長句…")
                }
                result != null -> {
                    val b = result!!
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(listOf("① 找主幹", "② 加回修飾", "③ 看完整句")[phase], style = MaterialTheme.typography.titleSmall)
                            Text(b.core, style = MaterialTheme.typography.bodyLarge)
                            Text(b.coreZh, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (phase >= 1) {
                                if (b.context.isNotBlank()) Text(b.context)
                                if (b.contextZh.isNotBlank()) Text(b.contextZh)
                                Text(b.explanation)
                            }
                            if (phase >= 2) {
                                Text(english)
                                Text(b.fullZh)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (phase > 0) OutlinedButton(onClick = { phase-- }) { Text("上一步") }
                                Button(onClick = { phase = if (phase == 2) 0 else phase + 1 }) {
                                    Text(if (phase == 2) "重新拆解" else "下一步")
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
                            try { result = SentenceBreakdownService.load(context, episodeId, startMs, english, settings) }
                            catch (e: Exception) { error = e.message ?: "拆解失敗" }
                            finally { busy = false }
                        }
                    }) { Text("重試") }
                }
            }
        }
    }
}
