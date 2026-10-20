package com.yichincho.englishbbc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSentenceBreakdownTest {
    private val original = "Although researchers expected good results, the evidence collected during the experiment suggested that factors were overlooked."
    private val valid = """{"pattern":"SVO","subject":"the evidence","verb":"suggested","remainder":"that factors were overlooked","skeletonZh":"證據顯示因素被忽略","chunks":[{"source":"Although researchers expected good results","role":"讓步子句","zh":"雖然研究員預期結果很好"},{"source":"collected during the experiment","role":"分詞修飾","zh":"實驗期間蒐集的"}],"summaryZh":"研究員原先樂觀，但結果顯示有因素被忽略。","simpleEnglish":"Researchers expected good results. However, the evidence showed that factors were overlooked."}"""

    @Test fun shortSentenceDoesNotQualify() {
        assertFalse(LongSentenceDetector.qualifies("The apple is red."))
    }

    @Test fun longSentenceQualifies() {
        assertTrue(LongSentenceDetector.qualifies(
            "Although researchers initially believed that the results would support their original theory, " +
                "the evidence collected during the experiment suggested that several important factors had been overlooked."
        ))
    }

    @Test fun moderatelyLongNestedClausesQualify() {
        assertTrue(LongSentenceDetector.qualifies(
            "The researchers found that students who reviewed the material each day were more likely to remember what they had learned."
        ))
    }

    @Test fun simpleMediumSentenceDoesNotQualify() {
        assertFalse(LongSentenceDetector.qualifies(
            "The team presented its findings at the meeting before returning to the office later that afternoon."
        ))
    }

    @Test fun punctuationDoesNotCountAsWords() {
        assertEquals(4, LongSentenceDetector.wordCount("The apple is red."))
    }

    @Test fun validJsonParsesAndRoundTrips() {
        val result = SentenceDna.parse(valid, original)
        assertEquals("SVO", result.pattern)
        assertEquals(2, result.chunks.size)
        assertEquals(result, SentenceDna.parse(result.toJson(), original))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingRequiredFieldsAreRejected() {
        SentenceDna.parse("""{"pattern":"SVO"}""", original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun inventedEnglishChunkIsRejected() {
        SentenceDna.parse(valid.replace("collected during the experiment", "invented by the model"), original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonStandardPatternIsRejected() {
        SentenceDna.parse(valid.replace("SVO", "SVOOO"), original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfOrderChunksAreRejected() {
        val reversed = """{"pattern":"SVO","subject":"the evidence","verb":"suggested","remainder":"","skeletonZh":"證據顯示","chunks":[{"source":"collected during the experiment","role":"修飾","zh":"蒐集"},{"source":"Although researchers expected good results","role":"讓步","zh":"雖然"}],"summaryZh":"摘要","simpleEnglish":"The evidence showed a problem."}"""
        SentenceDna.parse(reversed, original)
    }
}
