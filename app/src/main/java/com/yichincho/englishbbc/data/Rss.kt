package com.yichincho.englishbbc.data

import android.util.Xml
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale

object Rss {
    private const val MAX_ITEMS = 40

    /** Returns the channel title and its newest episodes. */
    fun fetch(url: String): Pair<String, List<Episode>> {
        val request = Request.Builder().url(url).build()
        Http.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw AiException("讀不到節目清單（HTTP ${resp.code}）")
            val body = resp.body ?: throw AiException("節目清單是空的")
            return body.byteStream().use { input ->
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(input, null)
                parse(p)
            }
        }
    }

    /** [p] must already have its input set and namespace processing off. */
    internal fun parse(p: XmlPullParser): Pair<String, List<Episode>> {
        var channelTitle = ""
        val episodes = ArrayList<Episode>()
        var inItem = false
        var f = HashMap<String, String>()

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && episodes.size < MAX_ITEMS) {
            if (event == XmlPullParser.START_TAG) {
                when (val name = p.name) {
                    "item" -> { inItem = true; f = HashMap() }
                    "enclosure", "ppg:enclosureSecure" ->
                        if (inItem) p.getAttributeValue(null, "url")?.let { f[name] = it }
                    "title", "guid", "pubDate", "itunes:duration", "description", "link" -> {
                        val text = readText(p)
                        if (inItem) f[name] = text
                        else if (name == "title" && channelTitle.isEmpty()) channelTitle = text
                    }
                }
            } else if (event == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                toEpisode(f, channelTitle)?.let { episodes.add(it) }
            }
            event = p.next()
        }
        return channelTitle to episodes
    }

    /** Reads the text of the current element; leaves the parser on its END_TAG. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    private fun toEpisode(f: Map<String, String>, feedTitle: String): Episode? {
        val audio = f["ppg:enclosureSecure"] ?: f["enclosure"]?.replaceFirst("http://", "https://") ?: return null
        val guid = f["guid"] ?: audio
        return Episode(
            id = guid.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(80),
            feedTitle = feedTitle,
            title = f["title"].orEmpty(),
            pubDateMs = parseDate(f["pubDate"].orEmpty()),
            durationSec = parseDuration(f["itunes:duration"].orEmpty()),
            audioUrl = audio,
            description = stripHtml(f["description"].orEmpty()),
            link = f["link"].orEmpty(),
        )
    }

    private fun parseDate(s: String): Long = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(s)?.time ?: 0
    } catch (e: Exception) {
        0
    }

    private fun parseDuration(s: String): Int {
        val parts = s.trim().split(":").map { it.toIntOrNull() ?: return 0 }
        return parts.fold(0) { acc, v -> acc * 60 + v }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("</p>|<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\n{2,}"), "\n").trim()
}
