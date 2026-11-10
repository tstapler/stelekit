// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import kotlinx.coroutines.CancellationException

/** Newest known success for a key (git supplies max of run log and foreground staleness). */
fun interface LastSuccessSource {
    suspend fun lastSuccessAtMs(key: TaskKey): Long?
}

/** Why a [ScheduleLateness.Late] schedule is late. */
sealed interface LateCause {
    data object Unattributed : LateCause

    /** The newest runs were deliberate policy skips, so the OS is not to blame. */
    data class DeliberateSkip(val reason: String) : LateCause
}

/** Late / OnTime / NotApplicable; the UI word is "Late", never "Behind" (that means commits behind). */
sealed interface ScheduleLateness {
    data object OnTime : ScheduleLateness
    data object NotApplicable : ScheduleLateness
    data class Late(val sinceMs: Long, val cause: LateCause) : ScheduleLateness

    companion object {
        /** Not configurable in v1; matches the git staleness factor. */
        const val LATE_MULTIPLE = 2

        private const val MS_PER_MINUTE = 60_000L
        private const val ATTRIBUTION_WINDOW = 3

        /**
         * [schedule] null means a one-shot kind. No success ever recorded, or one in the future
         * (clock moved back), is [OnTime]: no baseline means no false Late. [recentRuns] is newest-first.
         */
        fun of(
            enabled: Boolean,
            schedule: EffectiveSchedule?,
            lastSuccessAtMs: Long?,
            nowMs: Long,
            recentRuns: List<TaskRunResult> = emptyList(),
        ): ScheduleLateness {
            if (!enabled || schedule == null) return NotApplicable
            if (lastSuccessAtMs == null) return OnTime
            val age = nowMs - lastSuccessAtMs
            if (age <= 0 || age <= LATE_MULTIPLE * schedule.intervalMinutes * MS_PER_MINUTE) return OnTime
            return Late(age, attribute(recentRuns))
        }

        private fun attribute(recentRuns: List<TaskRunResult>): LateCause {
            val window = recentRuns.take(ATTRIBUTION_WINDOW)
            val skips = window.filterIsInstance<TaskRunResult.Skipped>()
            if (window.isEmpty() || skips.size != window.size) return LateCause.Unattributed
            val reason = skips.first().reason
            return if (skips.all { it.reason == reason }) LateCause.DeliberateSkip(reason) else LateCause.Unattributed
        }
    }
}

/**
 * The only entry point UI/presentation uses, so no caller reads the run log directly and gets a
 * false Late from a foreground-only success. A throwing [source] yields [ScheduleLateness.OnTime].
 */
suspend fun lateness(
    key: TaskKey,
    schedule: EffectiveSchedule?,
    source: LastSuccessSource,
    now: Long,
    enabled: Boolean = true,
    recentRuns: List<TaskRunResult> = emptyList(),
): ScheduleLateness {
    val last = try {
        source.lastSuccessAtMs(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return ScheduleLateness.of(enabled, schedule, last, now, recentRuns)
}
