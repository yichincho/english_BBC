package com.yichincho.englishbbc.data

import org.json.JSONObject

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
