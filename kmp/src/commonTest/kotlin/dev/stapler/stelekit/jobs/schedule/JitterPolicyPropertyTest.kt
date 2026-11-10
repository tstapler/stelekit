// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JitterPolicyPropertyTest {
    private val caps = SchedulerCapabilities(
        minIntervalMinutes = 15, minFlexMinutes = 5, supportsUnmetered = true, supportsCharging = true,
        supportsJitter = true, runsWhenClosed = true, schedulesWork = true,
    )

    @Test
    fun offsetMinutes_should_stayWithinZeroAndJitterAndBeDeterministic_When_AnySeed() = runTest {
        checkAll(Arb.long(), Arb.int(-50..500)) { seedValue, jitter ->
            val seed = JitterSeed.ofHash(seedValue)
            val a = JitterPolicy.offsetMinutes(seed, jitter)
            assertEquals(a, JitterPolicy.offsetMinutes(seed, jitter))
            assertTrue(a in 0..maxOf(0, jitter), "offset $a for jitter $jitter")
        }
    }

    @Test
    fun offsetMinutes_should_beEqualAndInRange_When_SeedFromStringTwice() {
        val seed = JitterSeed("git-sync:graph-a")
        val first = JitterPolicy.offsetMinutes(seed, 10)
        assertEquals(first, JitterPolicy.offsetMinutes(JitterSeed("git-sync:graph-a"), 10))
        assertTrue(first in 0..10)
    }

    @Test
    fun clamp_should_neverGoNegativeOrBelowMinInterval_When_InputsNegativeOrOversized() = runTest {
        checkAll(Arb.int(-100..2000), Arb.int(-100..3000), Arb.long()) { rawInterval, rawJitter, seedValue ->
            val e = Schedule.coerce(rawInterval, rawJitter).clamp(caps, JitterSeed.ofHash(seedValue))
            assertTrue(e.intervalMinutes >= caps.minIntervalMinutes)
            assertTrue(e.jitterMinutes >= 0 && e.offsetMinutes >= 0)
            assertTrue(e.offsetMinutes <= e.jitterMinutes)
            assertTrue(e.intervalMinutes + e.offsetMinutes >= caps.minIntervalMinutes)
            assertTrue(e.jitterMinutes <= e.intervalMinutes - caps.minFlexMinutes)
            val flex = e.flexMinutes
            if (e.jitterMinutes > 0) {
                assertTrue(flex != null && flex >= caps.minFlexMinutes && flex < e.intervalMinutes, "flex $flex")
            } else {
                assertNull(flex)
                assertEquals(0, e.offsetMinutes)
            }
        }
    }

    @Test
    fun clamp_should_clampCoercedRawInts_When_IntervalBelowMinAndJitterTooLarge() {
        val e = Schedule.coerce(10, 30).clamp(caps, JitterSeed("git-sync:graph-a"))
        assertEquals(15, e.intervalMinutes)
        assertEquals(10, e.jitterMinutes)
        assertEquals(10, e.flexMinutes)
        assertTrue(e.offsetMinutes in 0..10)
        assertTrue(e.clamped)
    }

    @Test
    fun clamp_should_limitJitterToIntervalMinusMinFlexAndReportClamped_When_JitterTooLarge() {
        val e = Schedule.of(60, 60).clamp(caps)
        assertEquals(60, e.intervalMinutes)
        assertEquals(55, e.jitterMinutes)
        assertEquals(55, e.flexMinutes)
        assertTrue(e.clamped)
    }

    @Test
    fun clamp_should_reproduceTodaysRequest_When_ZeroJitter() {
        val e = Schedule.of(60, 0).clamp(caps, JitterSeed("any"))
        assertNull(e.flexMinutes)
        assertEquals(0, e.offsetMinutes)
        assertFalse(e.clamped)
    }

    @Test
    fun clamp_should_zeroJitter_When_AdapterDoesNotSupportJitter() {
        val e = Schedule.of(60, 10).clamp(caps.copy(supportsJitter = false))
        assertEquals(0, e.jitterMinutes)
        assertNull(e.flexMinutes)
        assertTrue(e.clamped)
    }

    @Test
    fun clamp_should_notReportClamped_When_ScheduleWithinCapabilities() {
        assertFalse(Schedule.of(60, 6).clamp(caps).clamped)
    }

    @Test
    fun flexMinutes_should_beNullForZeroOrNegativeJitter() {
        assertNull(JitterPolicy.flexMinutes(60, 0))
        assertNull(JitterPolicy.flexMinutes(60, -3))
        assertEquals(5, JitterPolicy.flexMinutes(60, 2))
    }

    @Test
    fun nextDelayMs_should_beInIntervalPlusJitterRange_When_AnyInputs() = runTest {
        checkAll(Arb.int(-10..1000), Arb.int(-10..1000), Arb.int()) { interval, jitter, rs ->
            val ms = JitterPolicy.nextDelayMs(interval, jitter, Random(rs))
            val lo = maxOf(0, interval) * 60_000L
            val hi = lo + maxOf(0, jitter) * 60_000L
            assertTrue(ms in lo..hi, "$ms not in $lo..$hi")
            assertEquals(ms, JitterPolicy.nextDelayMs(interval, jitter, Random(rs)))
        }
    }

    @Test
    fun offsets_should_spreadAcrossAllBuckets_When_200KeysAndJitter30() {
        val buckets = (0 until 200)
            .map { JitterPolicy.offsetMinutes(JitterSeed.of(TaskKey(TaskKind("git-sync"), TaskScope("graph-$it"))), 30) / 5 }
            .toSet()
        assertEquals((0..6).toSet(), buckets)
    }

    @Test
    fun seedOfKey_should_equalSeedOfKindColonScope() {
        val key = TaskKey(TaskKind("git-sync"), TaskScope("graph-a"))
        assertEquals(JitterSeed("git-sync:graph-a"), JitterSeed.of(key))
    }

    @Test
    fun preset_should_matchPlanValues() {
        assertEquals(0, JitterPreset.Off.minutesFor(60))
        assertEquals(6, JitterPreset.ALittle.minutesFor(60))
        assertEquals(15, JitterPreset.ALot.minutesFor(60))
        assertEquals(1, JitterPreset.ALittle.minutesFor(1))
        assertEquals(1, JitterPreset.ALot.minutesFor(1))
    }

    @Test
    fun preset_should_neverExceedInterval_When_AnyInterval() = runTest {
        checkAll(Arb.int(1..5000)) { interval ->
            JitterPreset.entries.forEach { Schedule.of(interval, it.minutesFor(interval)) }
        }
    }

    @Test
    fun presetOf_should_roundTripPresetMinutes() {
        assertEquals(JitterPreset.Off, JitterPreset.presetOf(Schedule.of(60, 0)))
        assertEquals(JitterPreset.ALittle, JitterPreset.presetOf(Schedule.of(60, 6)))
        assertEquals(JitterPreset.ALot, JitterPreset.presetOf(Schedule.of(60, 15)))
        assertEquals(JitterPreset.ALittle, JitterPreset.presetOf(Schedule.of(60, 7)))
    }
}
