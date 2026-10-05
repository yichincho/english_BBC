package com.yichincho.englishbbc.data

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import kotlin.math.min

data class FeedState(val loading: Boolean = false, val episodes: List<Episode> = emptyList(), val error: String = "")

/** App-wide state and the download → transcript → translation pipeline. */
object Repo {
    private const val CHUNK_SEC = 600
    /** Each round translates only what is still missing, in smaller batches so a model that skips or cuts off loses less. */
    private val TRANSLATE_ROUNDS = listOf(25, 10, 5)

    private lateinit var filesDir: File
    private lateinit var settingsStore: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = MutableStateFlow(Settings())
    val library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val saved = MutableStateFlow<List<SavedSentence>>(emptyList())
    val transcripts = MutableStateFlow<Map<String, List<Sentence>>>(emptyMap())
    val feeds = MutableStateFlow<Map<String, FeedState>>(emptyMap())

    /** Episode id → what is being done to it right now. */
    val working = MutableStateFlow<Map<String, String>>(emptyMap())

    fun init(context: Context) {
        filesDir = context.filesDir
        File(filesDir, "audio").mkdirs()
        File(filesDir, "transcripts").mkdirs()
        settingsStore = SettingsStore(context)
        settings.value = settingsStore.load()
        library.value = readArray("library.json").mapObjects(::libraryItemFromJson).map {
            val item = it.copy(hasAudio = audioFile(it.episode.id).exists(), hasTranscript = transcriptComplete(it.episode.id))
            // Libraries saved before the counts existed get them once from the transcript itself.
            if (item.hasTranscript && item.sentenceCount == 0) item.withCounts(readTranscript(item.episode.id)) else item
        }
        saved.value = readArray("saved.json").mapObjects(::savedSentenceFromJson)
    }

    fun audioFile(id: String) = File(filesDir, "audio/$id.mp3")
    private fun transcriptFile(id: String) = File(filesDir, "transcripts/$id.json")

    /** Exists only while a transcript is unfinished; holds how many seconds of audio are already written down. */
    private fun progressFile(id: String) = File(filesDir, "transcripts/$id.progress")

    private fun transcriptComplete(id: String) = transcriptFile(id).exists() && !progressFile(id).exists()

    private fun readArray(name: String): JSONArray {
        val f = File(filesDir, name)
        return try {
            if (f.exists()) JSONArray(f.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /** [text] is read inside the lock so a slower, older write can never land on top of a newer one. */
    @Synchronized
    private fun writeAtomically(target: File, text: () -> String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text())
        tmp.renameTo(target)
    }

    private fun persistLibrary() = scope.launch {
        writeAtomically(File(filesDir, "library.json")) { library.value.toJsonArray { it.toJson() }.toString() }
    }

    private fun persistSaved() = scope.launch {
        writeAtomically(File(filesDir, "saved.json")) { saved.value.toJsonArray { it.toJson() }.toString() }
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        settings.update(transform)
        settingsStore.save(settings.value)
    }

    private fun updateItem(id: String, transform: LibraryItem.() -> LibraryItem) {
        library.update { list -> list.map { if (it.episode.id == id) it.transform() else it } }
        persistLibrary()
    }

    private fun setStage(id: String, stage: String) = working.update { it + (id to stage) }

    // ---- feeds ----

    fun loadFeed(url: String, force: Boolean = false) {
        val current = feeds.value[url]
        if (current != null && (current.loading || (!force && current.episodes.isNotEmpty()))) return
        feeds.update { it + (url to FeedState(loading = true, episodes = current?.episodes.orEmpty())) }
        scope.launch {
            val state = try {
                FeedState(episodes = Rss.fetch(url).second)
            } catch (e: Exception) {
                FeedState(episodes = current?.episodes.orEmpty(), error = e.message ?: "讀不到節目清單")
            }
            feeds.update { it + (url to state) }
        }
    }

    /** Checks that [url] is a podcast feed and adds it to the list. Returns an error message or null. */
    suspend fun addCustomFeed(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val (title, episodes) = Rss.fetch(url)
            if (episodes.isEmpty()) return@withContext "這個網址裡沒有可以聽的節目"
            updateSettings { s ->
                if (s.allFeeds().any { it.url == url }) s.copy(enabledFeeds = s.enabledFeeds + url)
                else s.copy(customFeeds = s.customFeeds + Feed(title.ifBlank { url }, url), enabledFeeds = s.enabledFeeds + url)
            }
            feeds.update { it + (url to FeedState(episodes = episodes)) }
            null
        } catch (e: Exception) {
            e.message ?: "讀不到這個網址"
        }
    }

    // ---- library ----

    fun add(episode: Episode) {
        if (library.value.any { it.episode.id == episode.id }) return
        library.update { listOf(LibraryItem(episode, System.currentTimeMillis())) + it }
        persistLibrary()
        prepare(episode.id)
    }

    fun remove(id: String) {
        library.update { list -> list.filterNot { it.episode.id == id } }
        transcripts.update { it - id }
        persistLibrary()
        scope.launch {
            audioFile(id).delete()
            transcriptFile(id).delete()
            progressFile(id).delete()
        }
    }

    fun savePosition(id: String, positionMs: Long) = updateItem(id) { copy(lastPositionMs = positionMs) }

    fun recordListened(id: String) = updateItem(id) { copy(listenCount = listenCount + 1, lastPositionMs = 0) }

    fun toggleSaved(episode: Episode, sentence: Sentence) {
        val exists = saved.value.any { it.episodeId == episode.id && it.startMs == sentence.startMs }
        saved.update { list ->
            if (exists) list.filterNot { it.episodeId == episode.id && it.startMs == sentence.startMs }
            else listOf(SavedSentence(episode.id, episode.title, sentence.startMs, sentence.en, sentence.zh)) + list
        }
        persistSaved()
    }

    fun removeSaved(s: SavedSentence) {
        saved.update { it - s }
        persistSaved()
    }

    // ---- transcripts ----

    fun loadTranscript(id: String) {
        if (transcripts.value.containsKey(id)) return
        scope.launch {
            val f = transcriptFile(id)
            if (!f.exists()) return@launch
            val list = try {
                JSONArray(f.readText()).mapObjects(::sentenceFromJson)
            } catch (e: Exception) {
                return@launch
            }
            transcripts.update { if (it.containsKey(id)) it else it + (id to list) }
        }
    }

    private fun readTranscript(id: String): List<Sentence> = try {
        JSONArray(transcriptFile(id).readText()).mapObjects(::sentenceFromJson)
    } catch (e: Exception) {
        emptyList()
    }

    private fun LibraryItem.withCounts(list: List<Sentence>) =
        copy(sentenceCount = list.size, translatedCount = list.count { it.zh.isNotBlank() })

    private fun saveTranscript(id: String, list: List<Sentence>) {
        writeAtomically(transcriptFile(id)) { list.toJsonArray { it.toJson() }.toString() }
        transcripts.update { it + (id to list) }
        updateItem(id) { withCounts(list) }
    }

    // ---- pipeline ----

    /** Does whatever is still missing for this episode: audio, transcript, translation. */
    fun prepare(id: String) {
        if (working.value.containsKey(id)) return
        val episode = library.value.firstOrNull { it.episode.id == id }?.episode ?: return
        setStage(id, "排隊中")
        scope.launch {
            try {
                updateItem(id) { copy(error = "") }
                if (!audioFile(id).exists()) download(episode)
                updateItem(id) { copy(hasAudio = true) }

                val s = settings.value
                if (!transcriptComplete(id)) {
                    if (s.geminiKey.isBlank()) throw AiException("還沒設定 Gemini 金鑰，所以只有聲音、沒有原稿")
                    transcribe(episode, s)
                }
                updateItem(id) { copy(hasTranscript = true) }
                translateMissing(id, s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateItem(id) { copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                working.update { it - id }
            }
        }
    }

    private fun download(episode: Episode) {
        val id = episode.id
        setStage(id, "下載聲音")
        val target = audioFile(id)
        val tmp = File(target.parentFile, target.name + ".part")
        Http.client.newCall(Request.Builder().url(episode.audioUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw AiException("聲音下載失敗（HTTP ${resp.code}）")
            val body = resp.body ?: throw AiException("聲音下載失敗")
            val total = body.contentLength()
            var done = 0L
            var lastShown = -1L
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) done * 100 / total else -1
                        if (percent != lastShown && percent >= 0) {
                            lastShown = percent
                            setStage(id, "下載聲音 $percent%")
                        }
                    }
                }
            }
        }
        if (!tmp.renameTo(target)) throw AiException("聲音存檔失敗")
    }

    private fun durationSecOf(file: File): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            ((r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000).toInt()
        } catch (e: Exception) {
            0
        } finally {
            r.release()
        }
    }

    private suspend fun transcribe(episode: Episode, s: Settings) {
        val id = episode.id
        val file = audioFile(id)
        val mime = if (episode.audioUrl.substringBefore('?').endsWith(".m4a")) "audio/aac" else "audio/mp3"

        setStage(id, "把聲音交給 Gemini")
        val uri = retrying { Gemini.upload(s.geminiKey, file, mime) }

        val total = durationSecOf(file).takeIf { it > 0 } ?: episode.durationSec.takeIf { it > 0 }
            ?: throw AiException("不知道這集有多長，沒辦法寫原稿")
        val context = "Programme: ${episode.feedTitle}\nEpisode: ${episode.title}\n${episode.description.take(1500)}"

        // Each finished chunk is saved straight away, so a failure or a closed app resumes here instead of starting over.
        val all = ArrayList<Sentence>()
        var from = 0
        val resumeAt = progressFile(id).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (resumeAt > 0 && transcriptFile(id).exists()) {
            all += JSONArray(transcriptFile(id).readText()).mapObjects(::sentenceFromJson)
            from = resumeAt
        }
        // The marker goes down before any transcript text, so a partly written transcript is never taken for a whole one.
        progressFile(id).writeText(from.toString())
        while (from < total) {
            val to = min(from + CHUNK_SEC, total)
            setStage(id, "寫原稿 ${from * 100 / total}%")
            val part = retrying { Gemini.transcribeRange(s.geminiKey, s.geminiModel, uri, mime, from, to, context) }
            val lastStart = all.lastOrNull()?.startMs ?: -1
            // Timestamps are whole seconds, so short sentences can share one; nudge them apart to keep the order strict.
            for (sentence in part.filter { it.startMs > lastStart }) {
                val previous = all.lastOrNull()?.startMs ?: -1
                all += if (sentence.startMs > previous) sentence else sentence.copy(startMs = previous + 400)
            }
            from = to
            saveTranscript(id, all.toList())
            progressFile(id).writeText(to.toString())
        }
        if (all.isEmpty()) {
            transcriptFile(id).delete()
            progressFile(id).delete()
            throw AiException("Gemini 沒有寫出原稿，可以再試一次或換型號")
        }
        progressFile(id).delete()
    }

    /**
     * Translates every sentence that has no Chinese yet. Models sometimes skip sentences or send back a reply
     * that is cut off, so after each round whatever is still blank is sent again in smaller batches.
     */
    private suspend fun translateMissing(id: String, s: Settings) {
        val provider = s.translatorProvider() ?: return
        var list = transcripts.value[id] ?: readTranscript(id)
        updateItem(id) { withCounts(list) }
        var unreadable: AiException? = null
        for (batchSize in TRANSLATE_ROUNDS) {
            val missing = list.withIndex().filter { it.value.zh.isBlank() }
            if (missing.isEmpty()) break
            for (batch in missing.chunked(batchSize)) {
                setStage(id, "翻成中文 ${list.count { it.zh.isNotBlank() }}/${list.size} 句")
                val translated = try {
                    retrying { Ai.translate(s, provider, batch) }
                } catch (e: AiException) {
                    // A reply that cannot be read is worth another round; an HTTP error (bad key, no quota) is not.
                    if (e.httpCode != 0) throw AiException("原稿好了，但翻譯失敗：${e.message}")
                    unreadable = e
                    continue
                }
                if (translated.isEmpty()) continue
                list = list.mapIndexed { i, sentence -> translated[i]?.takeIf { it.zh.isNotBlank() } ?: sentence }
                saveTranscript(id, list)
            }
        }
        val left = list.count { it.zh.isBlank() }
        if (left > 0) {
            val why = unreadable?.message?.let { "（$it）" }.orEmpty()
            throw AiException("中文翻了 ${list.size - left}/${list.size} 句，還有 $left 句沒翻好$why，可以按「補翻譯」再試")
        }
    }

    suspend fun testProvider(p: Provider, key: String, model: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            Result.success(Ai.verify(p, key, model))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
