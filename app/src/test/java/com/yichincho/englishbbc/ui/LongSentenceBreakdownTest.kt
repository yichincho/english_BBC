package com.yichincho.englishbbc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSentenceBreakdownTest {
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

    @Test fun validJsonParses() {
        val b = SentenceBreakdown.parse(
            """{"core":"The evidence suggested a problem.","coreZh":"證據顯示問題。","context":"Although ...","contextZh":"雖然……","explanation":"主要動詞 suggested。","fullZh":"完整翻譯。"}"""
        )
        assertEquals("The evidence suggested a problem.", b.core)
        assertEquals("完整翻譯。", b.fullZh)
        assertEquals(b, SentenceBreakdown.parse(b.toJson()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingRequiredFieldsAreRejected() {
        SentenceBreakdown.parse("""{"core":"","fullZh":"中文"}""")
    }
}
