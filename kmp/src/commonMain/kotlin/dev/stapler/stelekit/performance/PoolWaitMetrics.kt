// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.performance

/**
 * Implemented by JVM connection-pool drivers to expose pool wait-time statistics.
 * The driver accumulates wait times in thread-safe atomic counters; callers drain
 * those counters periodically (e.g. in the 5-second span-flush loop) and record
 * the values to a histogram or ring buffer.
 */
interface PoolWaitMetrics {
    /** Cumulative pool wait in milliseconds and call count since last drain, then reset to zero. */
    fun drainPoolWaitStats(): PoolWaitSnapshot?
}

data class PoolWaitSnapshot(val totalWaitMs: Long, val count: Long)
