// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicSyncDriverJitterTest {
    private val minuteMs = 60_000L

    /** Runs the loop for [cycles] ticks and returns the virtual time at which each tick fired. */
    private fun TestScope.tickTimes(interval: Int, jitter: Int, cycles: Int, random: Random): List<Long> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val times = mutableListOf<Long>()
        val driver = PeriodicSyncDriver(tick = { times += currentTime; TickOutcome.Success }, scope = scope, random = random)
        driver.start(interval, jitterMinutes = jitter)
        while (times.size < cycles) testScheduler.advanceTimeBy(minuteMs).also { testScheduler.runCurrent() }
        scope.cancel()
        return times
    }

    @Test
    fun delay_should_StayWithinIntervalPlusJitter_Across50SeededCycles() = runTest {
        val times = tickTimes(interval = 15, jitter = 5, cycles = 50, random = Random(42))
        val gaps = listOf(0L) + times
        val deltas = gaps.zipWithNext { a, b -> b - a }
        assertEquals(50, times.size)
        assertTrue(deltas.all { it in 15 * minuteMs..(15 + 5) * minuteMs }, "every delay in [interval, interval + jitter]: $deltas")
        assertTrue(deltas.toSet().size > 1, "jitter actually varies the delay")
    }

    @Test
    fun delay_should_EqualIntervalExactly_When_JitterIsZero() = runTest {
        val times = tickTimes(interval = 15, jitter = 0, cycles = 10, random = Random(7))
        assertEquals(List(10) { (it + 1) * 15 * minuteMs }, times)
    }
}
