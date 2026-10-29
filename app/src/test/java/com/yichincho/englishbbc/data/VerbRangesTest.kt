package com.yichincho.englishbbc.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerbRangesTest {
    private fun marked(english: String, verbs: List<String>) =
        verbRanges(english, verbs).map { english.substring(it.first, it.last + 1) }

    @Test fun marksEveryVerbAsWholeWords() {
        val en = "This is why they don't see eye to eye, and it should be revived."
        assertEquals(listOf("is", "don't see", "should be revived"), marked(en, listOf("is", "don't see", "should be revived")))
        assertEquals(5, verbRanges(en, listOf("is")).single().first) // not the "is" inside "This"
    }

    @Test fun repeatedVerbFollowsSentenceOrder() {
        val en = "It is what it is."
        assertEquals(listOf(3, 14), verbRanges(en, listOf("is", "is")).map { it.first })
    }

    @Test fun verbNotInSentenceIsSkipped() {
        assertEquals(listOf("align"), marked("Where do you guys align?", listOf("agree", "align")))
    }

    @Test fun verbsSurviveSavingAndOldTranscriptsHaveNone() {
        val s = Sentence(1000, "They align.", "他們一致。", verbs = listOf("align"))
        assertEquals(s, sentenceFromJson(s.toJson()))
        assertNull(sentenceFromJson(JSONObject().put("startMs", 0).put("en", "Old.")).verbs)
    }
}
