package com.yichincho.englishbbc.data

import com.yichincho.englishbbc.ui.topicAt
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class TopicsTest {
    private fun topic(start: Int, title: String = "T$start", points: String = """[{"en":"A point.","zh":"重點。"}]""") =
        """{"start":$start,"title":"$title","titleZh":"中文$start","points":$points}"""

    @Test fun topicsAreSortedAndTheFirstStartsAtZero() {
        val list = topicsFromJson(JSONArray("[${topic(40)},${topic(3)}]"), 100)
        assertEquals(listOf(0, 40), list.map { it.start })
        assertEquals("T3", list.first().title)
    }

    @Test fun badEntriesAreDroppedAndStartsClamped() {
        val list = topicsFromJson(JSONArray("[${topic(0)},${topic(5, title = "")},${topic(7, points = "[{\"en\":\"no zh\",\"zh\":\"\"}]")},${topic(999)}]"), 50)
        assertEquals(listOf(0, 49), list.map { it.start })
    }

    @Test fun duplicateStartsKeepOne() {
        assertEquals(1, topicsFromJson(JSONArray("[${topic(0)},${topic(0, "Again")}]"), 10).size)
    }

    @Test fun savedTopicsReadBack() {
        val list = topicsFromJson(JSONArray("[${topic(0)},${topic(12)}]"), 20)
        assertEquals(list, topicsFromJson(list.toJsonArray { it.toJson() }, 20))
    }

    @Test fun playingTopicFollowsTheCurrentSentence() {
        val list = topicsFromJson(JSONArray("[${topic(0)},${topic(10)},${topic(20)}]"), 30)
        assertEquals(-1, topicAt(list, -1))
        assertEquals(0, topicAt(list, 9))
        assertEquals(1, topicAt(list, 10))
        assertEquals(2, topicAt(list, 29))
    }
}
