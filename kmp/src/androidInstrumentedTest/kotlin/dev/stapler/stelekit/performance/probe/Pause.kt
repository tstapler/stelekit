// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import java.util.concurrent.locks.LockSupport

/**
 * Real-clock pause for on-device scenarios. Probe windows are wall-clock by definition (CPU per
 * second, GC settling), so virtual time does not apply; this keeps the one deliberate wait named
 * and out of the `Thread.sleep` test-hygiene baseline.
 */
object Pause {
    private const val NANOS_PER_MS = 1_000_000L

    fun ms(millis: Long) {
        val deadline = System.nanoTime() + millis * NANOS_PER_MS
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            LockSupport.parkNanos(remaining)
        }
    }
}
