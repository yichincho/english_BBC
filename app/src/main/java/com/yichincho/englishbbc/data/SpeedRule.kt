package com.yichincho.englishbbc.data

import java.time.LocalDateTime

/**
 * Daytime is always 1.0x. Night goes 1.2 → 1.4 → 1.6 → 1.8 → 2.0 over five days, then starts again.
 * Day and night boundaries are minutes since midnight.
 */
object SpeedRule {
    const val CYCLE_DAYS = 5

    private fun minuteOfDay(now: LocalDateTime) = now.hour * 60 + now.minute

    fun isNight(now: LocalDateTime, nightStartMinute: Int, dayStartMinute: Int): Boolean {
        val m = minuteOfDay(now)
        return if (nightStartMinute > dayStartMinute) m >= nightStartMinute || m < dayStartMinute
        else m in nightStartMinute until dayStartMinute
    }

    /** 0..4. The hours after midnight still count as the previous day's night. */
    fun cycleIndex(now: LocalDateTime, nightStartMinute: Int, dayStartMinute: Int, cycleStartEpochDay: Long): Int {
        val afterMidnight = nightStartMinute > dayStartMinute && minuteOfDay(now) < dayStartMinute
        val day = now.toLocalDate().toEpochDay() - if (afterMidnight) 1 else 0
        return Math.floorMod(day - cycleStartEpochDay, CYCLE_DAYS.toLong()).toInt()
    }

    fun nightSpeed(cycleIndex: Int): Float = (12 + 2 * cycleIndex) / 10f

    fun speedAt(now: LocalDateTime, s: Settings): Float =
        if (isNight(now, s.nightStartMinute, s.dayStartMinute))
            nightSpeed(cycleIndex(now, s.nightStartMinute, s.dayStartMinute, s.cycleStartEpochDay))
        else 1f
}
