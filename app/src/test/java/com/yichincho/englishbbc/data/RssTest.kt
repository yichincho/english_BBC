package com.yichincho.englishbbc.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.kxml2.io.KXmlParser

class RssTest {
    private fun parseSample(): Pair<String, List<Episode>> {
        val parser = KXmlParser()
        parser.setInput(javaClass.getResourceAsStream("/feed_sample.xml"), null)
        return Rss.parse(parser)
    }

    @Test
    fun readsChannelAndEpisodes() {
        val (title, episodes) = parseSample()
        assertEquals("Sample News Podcast", title)
        assertEquals(2, episodes.size)
    }

    @Test
    fun readsEpisodeFields() {
        val first = parseSample().second[0]
        assertEquals("First story & more", first.title)
        assertEquals("Sample News Podcast", first.feedTitle)
        assertEquals(1600, first.durationSec)
        assertEquals("https://open.example.com/proto/https/vpid/p0aaa.mp3", first.audioUrl)
        assertEquals("urn_bbc_podcast_p0aaa111", first.id)
        assertEquals("First paragraph.\nSecond paragraph with a link.", first.description)
        assertEquals(1790916480000L, first.pubDateMs)
    }

    @Test
    fun fallsBackToPlainEnclosureOverHttps() {
        val second = parseSample().second[1]
        assertEquals("https://open.example.com/proto/http/vpid/p0bbb.mp3", second.audioUrl)
        assertEquals(29 * 60 + 39, second.durationSec)
    }
}
