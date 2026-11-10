// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NoopTaskSchedulerTest {
    private val key = TaskKey(TaskKind("git-sync"), TaskScope("g1"))

    @Test
    fun capabilities_should_beAllFalse_When_Constructed() {
        val c = NoopTaskScheduler().capabilities
        assertFalse(c.schedulesWork)
        assertFalse(c.runsWhenClosed)
        assertFalse(c.supportsUnmetered)
        assertFalse(c.supportsCharging)
        assertFalse(c.supportsJitter)
    }

    @Test
    fun apply_should_scheduleNothing_When_EnabledConfigApplied() = runTest {
        val scheduler = NoopTaskScheduler()
        scheduler.apply(key, TaskConfig(true, Schedule.of(60, 5), TaskConstraints()))
        scheduler.runOnce(key, TaskConstraints(), OneShotConflict.Replace)
        scheduler.pause(key.scope)
        scheduler.resume(key.scope)
        assertNull(scheduler.describe(key))
        scheduler.cancel(key)
        scheduler.cancelAll(key.scope)
        assertEquals(null, scheduler.describe(key))
    }
}
