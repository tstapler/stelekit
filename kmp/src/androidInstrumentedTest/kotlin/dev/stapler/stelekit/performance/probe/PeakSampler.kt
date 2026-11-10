// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import android.os.Debug
import java.util.concurrent.locks.LockSupport

/**
 * Polls Java and native heap on its own thread; the peaks are what a heap cap has to absorb. Ported
 * from `AssetPipelineHeapGateTest.PeakHeapSampler` (20 ms) with native heap added.
 */
class PeakSampler(private val intervalMs: Long = SAMPLE_INTERVAL_MS) : AutoCloseable {
    @Volatile private var running = true
    @Volatile var peakJavaBytes = 0L
        private set
    @Volatile var peakNativeBytes = 0L
        private set

    private val thread = Thread {
        val rt = Runtime.getRuntime()
        while (running) {
            peakJavaBytes = maxOf(peakJavaBytes, rt.totalMemory() - rt.freeMemory())
            peakNativeBytes = maxOf(peakNativeBytes, Debug.getNativeHeapAllocatedSize())
            LockSupport.parkNanos(intervalMs * NANOS_PER_MS)
        }
    }.apply { isDaemon = true; name = "probe-peak-sampler"; start() }

    override fun close() {
        running = false
        thread.join()
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 20L
        const val NANOS_PER_MS = 1_000_000L
    }
}
