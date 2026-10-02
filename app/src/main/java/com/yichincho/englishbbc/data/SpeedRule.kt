package com.yichincho.englishbbc.data

import java.time.LocalDateTime

/** Daytime is always 1.0x. Night goes 1.2 → 1.4 → 1.6 → 1.8 → 2.0 over five days, then starts again. */
object SpeedRule {
    const val CYCLE_DAYS = 5

    fun isNight(hour: Int, nightStartHour: Int, dayStartHour: Int): Boolean =
        if (nightStartHour > dayStartHour) hour >= nightStartHour || hour < dayStartHour
        else hour in nightStartHour until dayStartHour

    /** 0..4. The hours after midnight still count as the previous day's night. */
    fun cycleIndex(now: LocalDateTime, nightStartHour: Int, dayStartHour: Int, cycleStartEpochDay: Long): Int {
        val afterMidnight = nightStartHour > dayStartHour && now.hour < dayStartHour
        val day = now.toLocalDate().toEpochDay() - if (afterMidnight) 1 else 0
        return Math.floorMod(day - cycleStartEpochDay, CYCLE_DAYS.toLong()).toInt()
    }

    fun nightSpeed(cycleIndex: Int): Float = (12 + 2 * cycleIndex) / 10f

    fun speedAt(now: LocalDateTime, s: Settings): Float =
        if (isNight(now.hour, s.nightStartHour, s.dayStartHour))
            nightSpeed(cycleIndex(now, s.nightStartHour, s.dayStartHour, s.cycleStartEpochDay))
        else 1f
}
