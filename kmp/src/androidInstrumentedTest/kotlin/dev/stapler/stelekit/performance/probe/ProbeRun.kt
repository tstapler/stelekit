// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.ui.components.sharedImageMemoryCache

/**
 * Wraps one scenario: settles, snapshots, samples peaks, and turns the deltas into a [ProbeRecord]
 * whose keys are all declared in [ProbeCatalog]. A metric the scenario computes but the catalog
 * lacks is an error, so nothing ships without a budget entry.
 */
class ProbeRun(private val context: Context, private val scenario: String) {
    private val probe = ResourceProbe(context)
    private val startPid = Process.myPid()
    private val startMs = System.currentTimeMillis()
    private lateinit var before: ProbeSnapshot
    private lateinit var peak: PeakSampler
    private var frames: FrameStats = FrameStats.EMPTY

    fun begin(): ProbeRun {
        probe.settle()
        before = probe.snapshot()
        peak = PeakSampler()
        return this
    }

    fun withFrames(stats: FrameStats): ProbeRun = apply { frames = stats }

    /** CPU ms burnt since [begin]; the numerator of every `cpuMsPer*` metric. */
    fun cpuMsSinceBegin(): Long = probe.snapshot().cpuMs - before.cpuMs

    /** [scenarioMetrics] are bare names (`maxDecodeBytes`); the standard resource metrics are added when cataloged. */
    fun finish(scenarioMetrics: Map<String, Double>): ProbeRecord {
        peak.close()
        val retainedJava = probe.settledJavaUsedBytes() - before.javaUsedBytes
        val after = probe.snapshot()
        val cache = sharedImageMemoryCache(context)
        val standard = mapOf(
            "javaRetainedMb" to retainedJava.toMb(),
            "nativeDeltaMb" to (after.nativeAllocatedBytes - before.nativeAllocatedBytes).toMb(),
            "cacheOverflowBytes" to maxOf(0L, cache.size - cache.maxSize).toDouble(),
            "jankyPct" to frames.jankyPct,
        )
        val merged = standard.filterKeys { "$scenario.$it" in ProbeCatalog.METRICS } + scenarioMetrics
        val unknown = merged.keys.filter { "$scenario.$it" !in ProbeCatalog.METRICS }
        require(unknown.isEmpty()) { "$scenario emits metrics missing from ProbeCatalog: $unknown" }
        return ProbeRecord(scenario, merged, deviceProfile(context), startMs)
    }

    /** The `Process` record: survival plus exit reasons newer than the scenario start (checker filters further). */
    fun processRecord(): ProbeRecord = ProbeRecord(
        scenario = "Process",
        metrics = mapOf("survived" to if (probe.processSurvived(startPid)) 1.0 else 0.0),
        device = deviceProfile(context),
        scenarioStartMs = startMs,
        exitReasons = probe.exitRecords(),
    )

    private fun Long.toMb() = toDouble() / BYTES_PER_MB

    companion object {
        private const val BYTES_PER_MB = 1024.0 * 1024.0

        /** `expectedRamMb` is the AVD `ram-size` the workflow passes; absent means the device is trusted as-is. */
        fun deviceProfile(context: Context): DeviceProfile {
            val expected = InstrumentationRegistry.getArguments().getString("expectedRamMb")?.toLongOrNull()
            return DeviceProfile.read(context, expected)
        }
    }
}
