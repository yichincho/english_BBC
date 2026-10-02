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
    private const val TRANSLATE_BATCH = 25

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
            it.copy(hasAudio = audioFile(it.episode.id).exists(), hasTranscript = transcriptFile(it.episode.id).exists())
        }
        saved.value = readArray("saved.json").mapObjects(::savedSentenceFromJson)
    }

    fun audioFile(id: String) = File(filesDir, "audio/$id.mp3")
    private fun transcriptFile(id: String) = File(filesDir, "transcripts/$id.json")

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

    private fun saveTranscript(id: String, list: List<Sentence>) {
        writeAtomically(transcriptFile(id)) { list.toJsonArray { it.toJson() }.toString() }
        transcripts.update { it + (id to list) }
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
                if (!transcriptFile(id).exists()) {
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

        val all = ArrayList<Sentence>()
        var from = 0
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
        }
        if (all.isEmpty()) throw AiException("Gemini 沒有寫出原稿，可以再試一次或換型號")
        saveTranscript(id, all)
    }

    private suspend fun translateMissing(id: String, s: Settings) {
        val provider = s.translatorProvider() ?: return
        var list = transcripts.value[id]
            ?: JSONArray(transcriptFile(id).readText()).mapObjects(::sentenceFromJson)
        val missing = list.withIndex().filter { it.value.zh.isBlank() }
        var done = 0
        for (batch in missing.chunked(TRANSLATE_BATCH)) {
            setStage(id, "翻成中文 ${done * 100 / missing.size}%")
            val translated = try {
                retrying { Ai.translate(s, provider, batch) }
            } catch (e: AiException) {
                throw AiException("原稿好了，但翻譯失敗：${e.message}")
            }
            list = list.mapIndexed { i, sentence -> translated[i] ?: sentence }
            saveTranscript(id, list)
            done += batch.size
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
