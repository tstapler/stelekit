// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import kotlin.math.abs

/** User-facing spread presets; [minutesFor] is the jitter window for an interval, never above it. */
enum class JitterPreset(private val percent: Int, private val floorMinutes: Int) {
    Off(0, 0),
    ALittle(10, 1),
    ALot(25, 2);

    fun minutesFor(intervalMinutes: Int): Int {
        if (this == Off) return 0
        val rounded = (intervalMinutes * percent + 50) / 100
        return maxOf(floorMinutes, rounded).coerceAtMost(maxOf(0, intervalMinutes))
    }

    companion object {
        /** [Off] only for zero jitter; otherwise the non-Off preset whose minutes are nearest (ties go to the smaller). */
        fun presetOf(schedule: Schedule): JitterPreset {
            if (schedule.jitterMinutes == 0) return Off
            return listOf(ALittle, ALot).minByOrNull { abs(it.minutesFor(schedule.intervalMinutes) - schedule.jitterMinutes) }!!
        }
    }
}
