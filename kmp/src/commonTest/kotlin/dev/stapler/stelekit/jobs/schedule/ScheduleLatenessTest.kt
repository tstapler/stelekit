// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScheduleLatenessTest {
    private val min = 60_000L
    private val now = 1_000_000_000_000L
    private val sixty = EffectiveSchedule(intervalMinutes = 60, jitterMinutes = 0, flexMinutes = null, offsetMinutes = 0, clamped = false)
    private val key = TaskKey(TaskKind("git-sync"), TaskScope("g"))

    private fun of(last: Long?, enabled: Boolean = true, schedule: EffectiveSchedule? = sixty, runs: List<TaskRunResult> = emptyList(), at: Long = now) =
        ScheduleLateness.of(enabled, schedule, last, at, runs)

    @Test
    fun of_should_returnLateAfterMultipleOfInterval_When_LastSuccess150MinAgo() {
        assertEquals(2, ScheduleLateness.LATE_MULTIPLE)
        assertEquals(ScheduleLateness.Late(150 * min, LateCause.Unattributed), of(now - 150 * min))
        assertEquals(ScheduleLateness.OnTime, of(now - 90 * min))
        assertEquals(ScheduleLateness.OnTime, of(now - 120 * min)) // exactly 2x is not late
    }

    @Test
    fun of_should_returnOnTime_When_LastSuccessInFutureOrForegroundSourceRecent() = runTest {
        assertEquals(ScheduleLateness.OnTime, of(now + 5 * min))
        val foreground = LastSuccessSource { now - 10 * min }
        val stale = listOf<TaskRunResult>(TaskRunResult.Skipped("x"))
        assertEquals(ScheduleLateness.OnTime, lateness(key, sixty, foreground, now, recentRuns = stale))
    }

    @Test
    fun of_should_returnOnTime_When_NoSuccessEverOrSourceThrows() = runTest {
        assertEquals(ScheduleLateness.OnTime, of(null))
        assertEquals(ScheduleLateness.OnTime, lateness(key, sixty, { error("boom") }, now))
        assertEquals(ScheduleLateness.OnTime, lateness(key, sixty, { null }, now))
    }

    @Test
    fun lateness_should_returnLate_When_SourceReportsOldSuccess() = runTest {
        val r = lateness(key, sixty, { now - 5 * 60 * min }, now)
        assertIs<ScheduleLateness.Late>(r)
        assertEquals(5 * 60 * min, r.sinceMs)
    }

    @Test
    fun of_should_attributeDeliberateSkip_When_LastRunsVaultLocked() {
        val runs = List(3) { TaskRunResult.Skipped("vault-locked") }
        assertEquals(
            ScheduleLateness.Late(5 * 60 * min, LateCause.DeliberateSkip("vault-locked")),
            of(now - 5 * 60 * min, runs = runs),
        )
        val mixed = listOf(TaskRunResult.Skipped("vault-locked"), TaskRunResult.Failed("x"), TaskRunResult.Skipped("vault-locked"))
        assertEquals(LateCause.Unattributed, (of(now - 5 * 60 * min, runs = mixed) as ScheduleLateness.Late).cause)
        val differing = listOf(TaskRunResult.Skipped("a"), TaskRunResult.Skipped("b"))
        assertEquals(LateCause.Unattributed, (of(now - 5 * 60 * min, runs = differing) as ScheduleLateness.Late).cause)
    }

    @Test
    fun of_should_returnNotApplicable_When_DisabledOrOneShot() {
        assertEquals(ScheduleLateness.NotApplicable, of(now - 999 * min, enabled = false))
        assertEquals(ScheduleLateness.NotApplicable, of(now - 999 * min, schedule = null))
    }

    @Test
    fun of_should_beMonotoneInNowAndNeverNegativeAge_When_AnyInputs() = runTest {
        checkAll(Arb.long(0L..10_000L), Arb.long(0L..10_000L), Arb.long(0L..10_000L)) { last, n1, d ->
            val a = of(last * min, at = n1 * min)
            val b = of(last * min, at = (n1 + d) * min)
            if (a is ScheduleLateness.Late) {
                assertTrue(b is ScheduleLateness.Late)
                assertTrue(a.sinceMs > 0)
            }
            if (b is ScheduleLateness.OnTime) assertFalse(a is ScheduleLateness.Late)
        }
    }

    @Test
    fun isRestricted_should_beTrueForStandbyAndFalseForNone_When_ProbeFake() {
        assertTrue(BackgroundRestrictionProbe { RestrictionReason.StandbyRestricted }.isRestricted())
        assertFalse(BackgroundRestrictionProbe { RestrictionReason.None }.isRestricted())
        RestrictionReason.entries.filter { it != RestrictionReason.None }.forEach {
            assertTrue(BackgroundRestrictionProbe { it }.isRestricted())
        }
    }
}
