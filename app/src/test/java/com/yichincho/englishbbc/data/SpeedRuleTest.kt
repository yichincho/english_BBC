package com.yichincho.englishbbc.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SpeedRuleTest {
    private val start = LocalDate.of(2026, 10, 2)
    private val s = Settings(nightStartMinute = 17 * 60 + 30, dayStartMinute = 6 * 60, cycleStartEpochDay = start.toEpochDay())

    private fun speed(dayOffset: Long, hour: Int, minute: Int = 0) =
        SpeedRule.speedAt(LocalDateTime.of(start.plusDays(dayOffset), java.time.LocalTime.of(hour, minute)), s)

    @Test
    fun daytimeIsAlwaysOne() {
        for (d in 0L..12L) assertEquals(1f, speed(d, 12), 0f)
    }

    @Test
    fun nightClimbsThenRepeats() {
        val expected = listOf(1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 1.2f, 1.4f)
        expected.forEachIndexed { d, v -> assertEquals(v, speed(d.toLong(), 20), 0.001f) }
    }

    @Test
    fun afterMidnightBelongsToPreviousNight() {
        assertEquals(speed(0, 23), speed(1, 2), 0.001f)
    }

    @Test
    fun boundaries() {
        assertEquals(1f, speed(0, 17, 29), 0f)
        assertEquals(1.2f, speed(0, 17, 30), 0.001f)
        assertEquals(1.2f, speed(1, 5, 59), 0.001f)
        assertEquals(1f, speed(1, 6), 0f)
    }

    @Test
    fun parsesTimestamps() {
        assertEquals(83_000L, parseTimestampMs("01:23"))
        assertEquals(3_723_000L, parseTimestampMs("1:02:03"))
        assertEquals(4_520_000L, parseTimestampMs("75:20"))
        assertEquals(1_500L, parseTimestampMs("00:01.5"))
        assertEquals(-1L, parseTimestampMs("abc"))
    }

    @Test
    fun repairsTruncatedJsonArray() {
        val arr = parseLenientArray("""[{"s":"00:01","t":"Hello."},{"s":"00:04","t":"Wor""")
        assertEquals(1, arr.size)
        assertEquals("Hello.", arr[0].second)
    }
}

class SuggestModelTest {
    @Test
    fun keepsModelThatStillExists() {
        assertEquals("b", Ai.suggestModel(Provider.DEEPSEEK, listOf("a", "b"), "b"))
    }

    @Test
    fun replacesRetiredModel() {
        val nvidia = listOf("meta/llama2-70b", "deepseek-ai/deepseek-coder-6.7b-instruct", "deepseek-ai/deepseek-v4.1-flash")
        assertEquals("deepseek-ai/deepseek-v4.1-flash", Ai.suggestModel(Provider.NVIDIA, nvidia, "meta/llama-3.3-70b-instruct"))
        val gemini = listOf("gemini-3-flash-lite", "gemini-3-flash", "gemini-2.5-flash", "gemini-3-flash-image")
        assertEquals("gemini-3-flash", Ai.suggestModel(Provider.GEMINI, gemini, "gemini-flash-latest"))
        assertEquals("gemini-flash-latest", Ai.suggestModel(Provider.GEMINI, gemini + "gemini-flash-latest", "gone"))
    }

    @Test
    fun keepsCurrentWhenListIsEmpty() {
        assertEquals("x", Ai.suggestModel(Provider.GEMINI, emptyList(), "x"))
    }
}
