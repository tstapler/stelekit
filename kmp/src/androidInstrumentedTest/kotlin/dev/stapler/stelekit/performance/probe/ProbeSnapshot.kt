// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import dev.stapler.stelekit.platform.diagnostics.ThreadCpuShare

/** One reading of every resource the budgets file cares about; deltas between two are the metrics. */
data class ProbeSnapshot(
    val wallNanos: Long,
    /** Process CPU (utime + stime) in milliseconds, from `/proc/self/stat`. */
    val cpuMs: Long,
    /** Top thread-name groups by CPU ms (cumulative), from `/proc/self/task/<tid>/stat`. */
    val threadCpuMs: List<ThreadCpuShare>,
    val javaUsedBytes: Long,
    val nativeAllocatedBytes: Long,
    val pssKb: Long,
    val graphicsKb: Long,
    /** Peak resident set (`VmHWM`) in KB. */
    val vmHwmKb: Long,
) {
    /** Every field is a count or a size, so a negative value means a failed read. */
    val allNonNegative: Boolean
        get() = listOf(wallNanos, cpuMs, javaUsedBytes, nativeAllocatedBytes, pssKb, graphicsKb, vmHwmKb).all { it >= 0 }

    operator fun minus(earlier: ProbeSnapshot): ProbeSnapshot = copy(
        wallNanos = wallNanos - earlier.wallNanos,
        cpuMs = cpuMs - earlier.cpuMs,
        javaUsedBytes = javaUsedBytes - earlier.javaUsedBytes,
        nativeAllocatedBytes = nativeAllocatedBytes - earlier.nativeAllocatedBytes,
        pssKb = pssKb - earlier.pssKb,
        graphicsKb = graphicsKb - earlier.graphicsKb,
    )
}

/** Frame timing from `Window.OnFrameMetricsAvailableListener`: percentiles in ms and the janky share. */
data class FrameStats(val frames: Int, val p50Ms: Double, val p95Ms: Double, val p99Ms: Double, val jankyPct: Double) {
    companion object {
        const val JANK_THRESHOLD_MS = 16.7
        val EMPTY = FrameStats(0, 0.0, 0.0, 0.0, 0.0)

        /** Nearest-rank percentiles over total frame durations in nanoseconds. */
        fun fromDurationsNanos(durations: List<Long>): FrameStats {
            if (durations.isEmpty()) return EMPTY
            val ms = durations.map { it / NANOS_PER_MS }.sorted()
            fun pct(p: Double) = ms[((ms.size * p).toInt()).coerceIn(0, ms.size - 1)]
            val janky = ms.count { it > JANK_THRESHOLD_MS }
            return FrameStats(ms.size, pct(0.50), pct(0.95), pct(0.99), janky * PERCENT / ms.size)
        }

        private const val NANOS_PER_MS = 1_000_000.0
        private const val PERCENT = 100.0
    }
}
