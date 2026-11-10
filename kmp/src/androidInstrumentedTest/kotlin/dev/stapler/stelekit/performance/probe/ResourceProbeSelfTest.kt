// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The probe must be able to see load (mutation burn) and report sane idle numbers (plan B.1c). */
@RunWith(AndroidJUnit4::class)
class ResourceProbeSelfTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val probe by lazy { ResourceProbe(context) }

    @Test
    fun idleSnapshots_should_BeNonNegative_AndCpuDeltaUnderTenPercentOfACore() {
        val first = probe.snapshot()
        Pause.ms(IDLE_WINDOW_MS)
        val second = probe.snapshot()
        assertTrue(first.allNonNegative && second.allNonNegative, "negative field: $first $second")
        val cpuDelta = second.cpuMs - first.cpuMs
        assertTrue(cpuDelta < IDLE_CPU_BUDGET_MS, "idle cpu delta ${cpuDelta}ms over ${IDLE_WINDOW_MS}ms")
    }

    @Test
    fun burnMutation_should_ShowMoreThan150MsCpuPerSecond() {
        val before = probe.snapshot()
        val deadline = System.nanoTime() + BURN_NANOS
        var sink = 0L
        while (System.nanoTime() < deadline) sink += sink xor System.nanoTime()
        val delta = probe.snapshot().cpuMs - before.cpuMs
        assertTrue(delta > MIN_BURN_CPU_MS, "probe saw only ${delta}ms for a ${BURN_NANOS / NANOS_PER_MS}ms busy loop ($sink)")
    }

    @Test
    fun frameStats_should_ComputePercentilesAndJankyShare() {
        val stats = FrameStats.fromDurationsNanos(List(100) { if (it < 90) 8_000_000L else 40_000_000L })
        assertEquals(100, stats.frames)
        assertEquals(10.0, stats.jankyPct, 0.001)
        assertTrue(stats.p50Ms < stats.p95Ms + 0.001 && stats.p95Ms <= stats.p99Ms)
    }

    @Test
    fun deviceProfile_should_CarryMemoryClassAndLargeHeapIntoTheRecordJson() {
        val json = ProbeRun.deviceProfile(context).toJson()
        assertTrue(json.getInt("memoryClass") > 0)
        assertEquals(false, json.getBoolean("largeHeap"))
        assertEquals(DeviceProfile.COIL_VERSION, json.getString("coilVersion"))
    }

    private companion object {
        const val IDLE_WINDOW_MS = 3_000L
        const val IDLE_CPU_BUDGET_MS = 300L
        const val BURN_NANOS = 2_000_000_000L
        const val MIN_BURN_CPU_MS = 300L
        const val NANOS_PER_MS = 1_000_000L
    }
}
