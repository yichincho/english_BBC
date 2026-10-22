package com.yichincho.englishbbc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSentenceBreakdownTest {
    private val original = "Although researchers expected good results, the evidence collected during the experiment suggested that factors were overlooked."
    private val valid = """{"pattern":"SVO","core":[{"label":"A","text":"Although researchers expected good results","zh":"雖然研究員預期結果很好","children":[]},{"label":"S","text":"the evidence collected during the experiment","zh":"實驗中蒐集的證據","children":[{"source":"collected during the experiment","kind":"分詞片語","function":"修飾 evidence","zh":"實驗期間蒐集的","children":[]}]},{"label":"V","text":"suggested","zh":"顯示","children":[]},{"label":"O","text":"that factors were overlooked","zh":"有因素被忽略","children":[{"source":"that factors were overlooked","kind":"名詞子句（that）","function":"作 suggested 的受詞","zh":"因素被忽略","children":[{"source":"were overlooked","kind":"被動語態","function":"說明 factors","zh":"被忽略","children":[]}]}]}],"skeletonZh":"證據顯示因素被忽略","summaryZh":"研究員原先樂觀，但結果顯示有因素被忽略。","simpleEnglish":"Researchers expected good results. However, the evidence showed that factors were overlooked."}"""
    private val legacyV2 = """{"pattern":"SVO","subject":"the evidence","verb":"suggested","remainder":"that factors were overlooked","skeletonZh":"證據顯示因素被忽略","chunks":[{"source":"Although researchers expected good results","role":"讓步子句","zh":"雖然研究員預期結果很好"}],"summaryZh":"摘要","simpleEnglish":"The evidence showed a problem."}"""

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

    @Test fun validJsonParsesNestedLayersAndRoundTrips() {
        val result = SentenceDna.parse(valid, original)
        assertEquals("SVO", result.pattern)
        assertEquals(listOf("A", "S", "V", "O"), result.core.map { it.label })
        assertEquals(2, result.core.last().children.first().depth())
        assertEquals(result, SentenceDna.parse(result.toJson(), original))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingRequiredFieldsAreRejected() {
        SentenceDna.parse("""{"pattern":"SVO"}""", original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun inventedEnglishInNestedNodeIsRejected() {
        SentenceDna.parse(valid.replace("\"source\":\"were overlooked\"", "\"source\":\"invented by the model\""), original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonStandardPatternIsRejected() {
        SentenceDna.parse(valid.replace("SVO", "SVOOO"), original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCoreLabelIsRejected() {
        SentenceDna.parse(valid.replace("\"label\":\"O\"", "\"label\":\"X\""), original)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfOrderCoreIsRejected() {
        val reversed = """{"pattern":"SV","core":[{"label":"V","text":"suggested","zh":"","children":[]},{"label":"S","text":"the evidence","zh":"","children":[]}],"skeletonZh":"證據顯示","summaryZh":"摘要","simpleEnglish":"The evidence showed a problem."}"""
        SentenceDna.parse(reversed, original)
    }

    @Test fun legacyV2AnalysisIsStillReadable() {
        val result = SentenceDna.fromLegacy(legacyV2, original)
        assertTrue(result.legacy)
        assertEquals(listOf("S", "V", "O", "A"), result.core.map { it.label })
        assertEquals("讓步子句", result.core.last().children.single().kind)
    }
}
