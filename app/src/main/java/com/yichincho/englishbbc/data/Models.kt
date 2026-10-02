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

data class LibraryItem(
    val episode: Episode,
    val addedAtMs: Long,
    val listenCount: Int = 0,
    val lastPositionMs: Long = 0,
    val hasAudio: Boolean = false,
    val hasTranscript: Boolean = false,
    val error: String = "",
)

data class Word(val en: String, val zh: String)

data class Sentence(
    val startMs: Long,
    val en: String,
    val zh: String = "",
    val words: List<Word> = emptyList(),
)

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

fun libraryItemFromJson(o: JSONObject) = LibraryItem(
    episode = episodeFromJson(o.getJSONObject("episode")),
    addedAtMs = o.optLong("addedAtMs"),
    listenCount = o.optInt("listenCount"),
    lastPositionMs = o.optLong("lastPositionMs"),
    hasAudio = o.optBoolean("hasAudio"),
    hasTranscript = o.optBoolean("hasTranscript"),
    error = o.optString("error"),
)

fun Sentence.toJson(): JSONObject = JSONObject()
    .put("startMs", startMs).put("en", en).put("zh", zh)
    .put("words", JSONArray().also { arr -> words.forEach { arr.put(JSONObject().put("en", it.en).put("zh", it.zh)) } })

fun sentenceFromJson(o: JSONObject): Sentence {
    val arr = o.optJSONArray("words") ?: JSONArray()
    return Sentence(
        startMs = o.optLong("startMs"),
        en = o.optString("en"),
        zh = o.optString("zh"),
        words = (0 until arr.length()).map { arr.getJSONObject(it) }.map { Word(it.optString("en"), it.optString("zh")) },
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
