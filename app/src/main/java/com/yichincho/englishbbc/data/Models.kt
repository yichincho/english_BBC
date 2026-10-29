package com.yichincho.englishbbc.data

import org.json.JSONArray
import org.json.JSONObject

data class Feed(val title: String, val url: String)

data class Episode(
    val id: String,
    val feedTitle: String,
    val title: String,
    val pubDateMs: Long,
    val durationSec: Int,
    val audioUrl: String,
    val description: String,
    val link: String,
)

/** A stage is a short headline, optionally followed by detail lines after a newline. */
fun stageHeadline(stage: String) = stage.substringBefore('\n')

fun stageDetail(stage: String) = stage.substringAfter('\n', "")

data class LibraryItem(
    val episode: Episode,
    val addedAtMs: Long,
    val listenCount: Int = 0,
    val lastPositionMs: Long = 0,
    val hasAudio: Boolean = false,
    val hasTranscript: Boolean = false,
    val error: String = "",
    /** Sentences in the transcript, and how many of them have Chinese. */
    val sentenceCount: Int = 0,
    val translatedCount: Int = 0,
) {
    val translationComplete get() = sentenceCount > 0 && translatedCount >= sentenceCount
}

data class Word(val en: String, val zh: String)

data class Sentence(
    val startMs: Long,
    val en: String,
    val zh: String = "",
    val words: List<Word> = emptyList(),
    /** Verbs exactly as written in [en]; null until the AI has tagged this sentence. */
    val verbs: List<String>? = null,
)

/**
 * Character ranges of [verbs] in [english], matched as whole words and in order, so "is" never lights up
 * inside "this" and a verb used twice is marked where the AI meant it.
 */
fun verbRanges(english: String, verbs: List<String>): List<IntRange> {
    val found = mutableListOf<IntRange>()
    var offset = 0
    for (v in verbs.map { it.trim() }.filter { it.isNotEmpty() }) {
        val pattern = Regex("(?<![A-Za-z'’])" + Regex.escape(v) + "(?![A-Za-z'’])", RegexOption.IGNORE_CASE)
        val hit = pattern.find(english, offset) ?: pattern.find(english) ?: continue
        found += hit.range
        if (hit.range.first >= offset) offset = hit.range.last + 1
    }
    return found
}

data class SavedSentence(
    val episodeId: String,
    val episodeTitle: String,
    val startMs: Long,
    val en: String,
    val zh: String,
)

fun Episode.toJson(): JSONObject = JSONObject()
    .put("id", id).put("feedTitle", feedTitle).put("title", title)
    .put("pubDateMs", pubDateMs).put("durationSec", durationSec)
    .put("audioUrl", audioUrl).put("description", description).put("link", link)

fun episodeFromJson(o: JSONObject) = Episode(
    id = o.getString("id"),
    feedTitle = o.optString("feedTitle"),
    title = o.optString("title"),
    pubDateMs = o.optLong("pubDateMs"),
    durationSec = o.optInt("durationSec"),
    audioUrl = o.optString("audioUrl"),
    description = o.optString("description"),
    link = o.optString("link"),
)

fun LibraryItem.toJson(): JSONObject = JSONObject()
    .put("episode", episode.toJson()).put("addedAtMs", addedAtMs)
    .put("listenCount", listenCount).put("lastPositionMs", lastPositionMs)
    .put("hasAudio", hasAudio).put("hasTranscript", hasTranscript).put("error", error)
    .put("sentenceCount", sentenceCount).put("translatedCount", translatedCount)

fun libraryItemFromJson(o: JSONObject) = LibraryItem(
    episode = episodeFromJson(o.getJSONObject("episode")),
    addedAtMs = o.optLong("addedAtMs"),
    listenCount = o.optInt("listenCount"),
    lastPositionMs = o.optLong("lastPositionMs"),
    hasAudio = o.optBoolean("hasAudio"),
    hasTranscript = o.optBoolean("hasTranscript"),
    error = o.optString("error"),
    sentenceCount = o.optInt("sentenceCount"),
    translatedCount = o.optInt("translatedCount"),
)

fun Sentence.toJson(): JSONObject = JSONObject()
    .put("startMs", startMs).put("en", en).put("zh", zh)
    .put("words", JSONArray().also { arr -> words.forEach { arr.put(JSONObject().put("en", it.en).put("zh", it.zh)) } })
    .also { o -> verbs?.let { v -> o.put("v", JSONArray(v)) } }

fun sentenceFromJson(o: JSONObject): Sentence {
    val arr = o.optJSONArray("words") ?: JSONArray()
    return Sentence(
        startMs = o.optLong("startMs"),
        en = o.optString("en"),
        zh = o.optString("zh"),
        words = (0 until arr.length()).map { arr.getJSONObject(it) }.map { Word(it.optString("en"), it.optString("zh")) },
        verbs = o.optJSONArray("v")?.let { v -> (0 until v.length()).map { v.optString(it) } },
    )
}

fun SavedSentence.toJson(): JSONObject = JSONObject()
    .put("episodeId", episodeId).put("episodeTitle", episodeTitle)
    .put("startMs", startMs).put("en", en).put("zh", zh)

fun savedSentenceFromJson(o: JSONObject) = SavedSentence(
    episodeId = o.optString("episodeId"),
    episodeTitle = o.optString("episodeTitle"),
    startMs = o.optLong("startMs"),
    en = o.optString("en"),
    zh = o.optString("zh"),
)

fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

fun <T> List<T>.toJsonArray(f: (T) -> JSONObject): JSONArray = JSONArray().also { arr -> forEach { arr.put(f(it)) } }
